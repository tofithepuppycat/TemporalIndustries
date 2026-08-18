package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.device.ChronoRecording;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.List;

/**
 * Handheld action recorder. First right-click starts recording the wielder's movement
 * (position relative to the start point, look direction, crouch state) once per tick; a second
 * right-click stops and saves it onto the item itself, ready to be inserted into an Echo
 * Projector. Everything lives in the stack's {@link DataComponents#CUSTOM_DATA} — nothing is
 * stored server-side, so the recording travels with the item.
 */
@SuppressWarnings("null")
public class EchoRecordItem extends Item {
    /** 60 seconds at 20 ticks/second — keeps a saved recording's NBT bounded. */
    private static final int MAX_FRAMES = 20 * 60;

    public EchoRecordItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer)) {
            return InteractionResultHolder.success(stack);
        }

        CompoundTag data = readData(stack);
        if (data.getBoolean("Recording")) {
            stopRecording(stack, data, player, level);
        } else if (data.getBoolean("Saved")) {
            if (player.isCrouching()) {
                clearRecording(stack, data, player, level);
            } else {
                player.displayClientMessage(Component.translatable("item.temporalindustries.echo_record.clear_hint"), true);
            }
        } else {
            startRecording(stack, data, player, level);
        }
        return InteractionResultHolder.success(stack);
    }

    private void startRecording(ItemStack stack, CompoundTag data, Player player, Level level) {
        data.putBoolean("Recording", true);
        data.putBoolean("Saved", false);
        data.putDouble("StartX", player.getX());
        data.putDouble("StartY", player.getY());
        data.putDouble("StartZ", player.getZ());
        data.putString("StartDimension", level.dimension().location().toString());
        data.putLong("StartGameTime", level.getGameTime());
        data.putUUID("OwnerId", player.getUUID());
        data.putString("OwnerName", player.getName().getString());
        data.put("Frames", new ListTag());
        data.put("Actions", new ListTag());
        writeData(stack, data);

        player.displayClientMessage(Component.translatable("item.temporalindustries.echo_record.started"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.6F);
    }

    private void stopRecording(ItemStack stack, CompoundTag data, Player player, Level level) {
        int frameCount = data.getList("Frames", Tag.TAG_COMPOUND).size();
        data.putBoolean("Recording", false);
        data.putBoolean("Saved", frameCount >= 2);
        writeData(stack, data);

        if (frameCount < 2) {
            player.displayClientMessage(Component.translatable("item.temporalindustries.echo_record.too_short"), true);
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0F, 1.0F);
            return;
        }

        player.displayClientMessage(Component.translatable("item.temporalindustries.echo_record.saved",
                String.format("%.1f", frameCount / 20.0)), true);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 0.7F);
    }

    /** Discards a saved-but-not-yet-inserted recording so the recorder can be reused. Only reachable
     * via crouch right-click (see use()) so a normal right-click on a saved recorder never destroys
     * it by accident. */
    private void clearRecording(ItemStack stack, CompoundTag data, Player player, Level level) {
        data.putBoolean("Saved", false);
        data.put("Frames", new ListTag());
        data.put("Actions", new ListTag());
        writeData(stack, data);

        player.displayClientMessage(Component.translatable("item.temporalindustries.echo_record.cleared"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        // Deliberately NOT gated on isSelected: recording must keep sampling movement even while
        // the player is wielding a different tool to mine/place (see ChronoActionRecorder), as
        // long as the Echo Record itself is still somewhere in their inventory.
        if (!(level instanceof ServerLevel serverLevel) || !(entity instanceof ServerPlayer player)) return;

        CompoundTag data = readData(stack);
        boolean recording = data.getBoolean("Recording");
        if (recording) spawnStartMarker(data, serverLevel);
        if (!recording) return;

        ListTag frames = data.getList("Frames", Tag.TAG_COMPOUND);
        if (frames.size() >= MAX_FRAMES) {
            stopRecording(stack, data, player, level);
            player.displayClientMessage(Component.translatable("item.temporalindustries.echo_record.limit_reached"), true);
            return;
        }

        double startX = data.getDouble("StartX");
        double startY = data.getDouble("StartY");
        double startZ = data.getDouble("StartZ");

        CompoundTag frame = new CompoundTag();
        frame.putFloat("X", (float) (player.getX() - startX));
        frame.putFloat("Y", (float) (player.getY() - startY));
        frame.putFloat("Z", (float) (player.getZ() - startZ));
        frame.putFloat("Yaw", player.getYRot());
        frame.putFloat("Pitch", player.getXRot());
        frame.putBoolean("Crouch", player.isCrouching());
        frames.add(frame);
        data.put("Frames", frames);
        writeData(stack, data);
    }

    private static final DustParticleOptions START_MARKER_PARTICLE =
            new DustParticleOptions(new Vector3f(0.6F, 0.1F, 0.95F), 1.3F);

    /** Marks wherever the current recording started with particles at ground level, for as long
     * as it's actively being recorded — a visual reminder of where to return to so the loop closes
     * cleanly, in the same dimension it was recorded in. */
    private void spawnStartMarker(CompoundTag data, ServerLevel serverLevel) {
        if (serverLevel.getGameTime() % 10 != 0) return;

        ResourceLocation dimension = ResourceLocation.tryParse(data.getString("StartDimension"));
        if (dimension == null || !dimension.equals(serverLevel.dimension().location())) return;

        double x = data.getDouble("StartX");
        double y = data.getDouble("StartY") + 0.1 + 0.05 * Math.sin(serverLevel.getGameTime() / 10.0);
        double z = data.getDouble("StartZ");
        serverLevel.sendParticles(START_MARKER_PARTICLE, x, y, z, 8, 0.25, 0.1, 0.25, 0.0);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag data = readData(stack);
        int frameCount = data.getList("Frames", Tag.TAG_COMPOUND).size();
        if (data.getBoolean("Recording")) {
            tooltip.add(Component.translatable("item.temporalindustries.echo_record.tooltip_recording",
                    String.format("%.1f", frameCount / 20.0)).withStyle(ChatFormatting.RED));
        } else if (data.getBoolean("Saved")) {
            tooltip.add(Component.translatable("item.temporalindustries.echo_record.tooltip_saved",
                    String.format("%.1f", frameCount / 20.0)).withStyle(ChatFormatting.AQUA));
            ChronoRecording.fromStack(stack).ifPresent(recording ->
                    tooltip.add(Component.translatable("item.temporalindustries.echo_record.tooltip_energy",
                            String.format("%.1f", recording.averageEnergyPerTick()), recording.peakEnergyPerTick())
                            .withStyle(ChatFormatting.GRAY)));
        } else {
            tooltip.add(Component.translatable("item.temporalindustries.echo_record.tooltip").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Whether stack is an Echo Record actively recording (used by ChronoActionRecorder to find
     * which, if any, of a player's items should capture a block break/place they just performed). */
    public static boolean isRecording(ItemStack stack) {
        return stack.getItem() instanceof EchoRecordItem && readData(stack).getBoolean("Recording");
    }

    public static CompoundTag readData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static void writeData(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
