package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.network.GlueRegionRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.GlueRegionSyncPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * Handheld area-protection tool, similar in spirit to Create's Super Glue: right-click one corner,
 * then another, to glue that cuboid region — every block inside is skipped when a delta would be
 * captured ({@link io.github.tofithepuppycat.temporalindustries.device.TemporalChangeListener#shouldRecord})
 * and when a rollback/jump would otherwise overwrite it ({@link io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline}'s
 * isGlued predicate param), so it sits outside the timeline entirely. Left-click while aiming at an
 * existing glued region (there can be several overlapping) to delete every region along your sight line.
 * Regions are stored in {@link TemporalWorldData}, not on the item — the item only ever holds a
 * pending first corner, in its {@link DataComponents#CUSTOM_DATA}, mirroring
 * {@link EchoRecordItem}'s per-stack storage. While held, both the pending corner and every known
 * glued region are drawn by {@link io.github.tofithepuppycat.temporalindustries.client.GlueSelectionRenderer}
 * — see {@link GlueRegionRequestPacket}/{@link GlueRegionSyncPacket} for how the client learns
 * which regions exist.
 */
@SuppressWarnings("null")
public class TemporalGlueItem extends Item {
    /** How often (in ticks) the held item polls the server for the current region list. */
    private static final int POLL_INTERVAL_TICKS = 20;

    public TemporalGlueItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos().immutable();
        CompoundTag data = readData(stack);

        if (data.contains("Pos1X")) {
            BlockPos pos1 = new BlockPos(data.getInt("Pos1X"), data.getInt("Pos1Y"), data.getInt("Pos1Z"));
            BoundingBox region = BoundingBox.fromCorners(pos1, clicked);
            TemporalWorldData.get(serverLevel.getServer()).addGluedRegion(serverLevel.dimension().location(), region);
            clearData(stack);
            stack.hurtAndBreak(1, serverLevel, player, item -> {});
            broadcastRegions(serverLevel);

            player.displayClientMessage(Component.translatable("item.temporalindustries.temporal_glue.glued"), true);
            level.playSound(null, clicked, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.PLAYERS, 1.0F, 1.0F);
        } else {
            data.putInt("Pos1X", clicked.getX());
            data.putInt("Pos1Y", clicked.getY());
            data.putInt("Pos1Z", clicked.getZ());
            writeData(stack, data);

            player.displayClientMessage(Component.translatable("item.temporalindustries.temporal_glue.corner_set"), true);
            level.playSound(null, clicked, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
        }
        return InteractionResult.SUCCESS;
    }

    /** Left-click-with-glue handler, called from TemporalChangeListener (which cancels the block
     * break that would otherwise start, or the swing-at-air interaction) for every left click while
     * holding Temporal Glue. Deletes every glued region whose bounding box lies anywhere along the
     * player's line of sight out to their block reach — so aiming roughly at a glued section is
     * enough, no need to land the click on an exact block inside it — or clears a pending first
     * corner if nothing was hit and one is pending. */
    public static void deleteRegionsAlongSight(ServerLevel level, ServerPlayer player, ItemStack stack) {
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        Vec3 origin = player.getEyePosition();
        Vec3 end = origin.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));

        int removed = worldData.removeGluedRegionsAlongRay(level.dimension().location(), origin, end);
        if (removed > 0) {
            player.displayClientMessage(Component.translatable("item.temporalindustries.temporal_glue.unglued"), true);
            level.playSound(null, player.blockPosition(), SoundEvents.SLIME_BLOCK_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);
            broadcastRegions(level);
            return;
        }

        CompoundTag data = readData(stack);
        if (data.contains("Pos1X")) {
            clearData(stack);
            player.displayClientMessage(Component.translatable("item.temporalindustries.temporal_glue.selection_cleared"), true);
        }
    }

    /** Whether player is holding Temporal Glue in either hand — used by TemporalChangeListener to
     * stop creative-mode instant break from destroying the block a glue/unglue click targets. */
    public static boolean isHolding(Player player) {
        return player.getMainHandItem().getItem() instanceof TemporalGlueItem
                || player.getOffhandItem().getItem() instanceof TemporalGlueItem;
    }

    /** Pushes the current region list to every player in level's dimension, so a glue/unglue is
     * reflected in everyone's in-world preview immediately instead of waiting for their next poll. */
    private static void broadcastRegions(ServerLevel level) {
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        PacketDistributor.sendToPlayersInDimension(level,
                new GlueRegionSyncPacket(dimension, worldData.getGluedRegions(dimension)));
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!isSelected || !level.isClientSide) return;
        if (level.getGameTime() % POLL_INTERVAL_TICKS != 0) return;

        PacketDistributor.sendToServer(GlueRegionRequestPacket.INSTANCE);
    }

    /** The pending first corner stored on stack, if any — read by
     * {@link io.github.tofithepuppycat.temporalindustries.client.GlueSelectionRenderer} to draw it. */
    public static Optional<BlockPos> getPendingCorner(ItemStack stack) {
        CompoundTag data = readData(stack);
        if (!data.contains("Pos1X")) return Optional.empty();
        return Optional.of(new BlockPos(data.getInt("Pos1X"), data.getInt("Pos1Y"), data.getInt("Pos1Z")));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag data = readData(stack);
        if (data.contains("Pos1X")) {
            tooltip.add(Component.translatable("item.temporalindustries.temporal_glue.tooltip_pending").withStyle(ChatFormatting.YELLOW));
        }
        tooltip.add(Component.translatable("item.temporalindustries.temporal_glue.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.temporalindustries.temporal_glue.tooltip_remove").withStyle(ChatFormatting.GRAY));
    }

    private static CompoundTag readData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void writeData(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clearData(ItemStack stack) {
        stack.remove(DataComponents.CUSTOM_DATA);
    }
}
