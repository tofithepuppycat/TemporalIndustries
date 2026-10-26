package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.network.GlueRegionRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.GlueRegionSyncPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Handheld tool that picks what a Chronosphere records by area instead of by chunk: link it to a
 * Chronosphere, then right-click one corner and another to include that cuboid in the Chronosphere's
 * commits. The chunks the cuboid spans are claimed automatically, but only the glued blocks inside
 * them are captured and restored.
 */
@SuppressWarnings("null")
public class TemporalGlueItem extends Item {
    /** How often (in ticks) the held item polls the server for the linked Chronosphere's regions. */
    private static final int POLL_INTERVAL_TICKS = 20;
    private static final String PENDING_X = "Pos1X";
    private static final String PENDING_Y = "Pos1Y";
    private static final String PENDING_Z = "Pos1Z";
    private static final String MESSAGE_PREFIX = "item.temporalindustries.temporal_glue.";

    public TemporalGlueItem(Properties properties) {
        super(properties);
    }

    /** The Chronosphere stack is linked to, or null if it hasn't been linked yet. */
    @Nullable
    public static GlobalPos getLinkedChronosphere(ItemStack stack) {
        return stack.get(Registration.LINKED_CHRONOSPHERE.get());
    }

    /** Runs before the block's own interaction, so right-clicking a Chronosphere links it instead of opening it. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof ChronosphereBlockEntity)) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide && context.getPlayer() != null) {
            stack.set(Registration.LINKED_CHRONOSPHERE.get(), GlobalPos.of(level.dimension(), context.getClickedPos()));
            clearPending(stack);
            context.getPlayer().displayClientMessage(Component.translatable(MESSAGE_PREFIX + "linked"), true);
            level.playSound(null, context.getClickedPos(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        ItemStack stack = context.getItemInHand();
        if (getLinkedChronosphere(stack) == null) {
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "not_linked"), true);
            return InteractionResult.SUCCESS;
        }

        BlockPos clicked = context.getClickedPos().immutable();
        Optional<BlockPos> first = getPendingCorner(stack);
        if (first.isEmpty()) {
            CompoundTag data = readData(stack);
            data.putInt(PENDING_X, clicked.getX());
            data.putInt(PENDING_Y, clicked.getY());
            data.putInt(PENDING_Z, clicked.getZ());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));

            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "corner_set"), true);
            level.playSound(null, clicked, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
            return InteractionResult.SUCCESS;
        }

        clearPending(stack);
        ChronosphereBlockEntity machine = resolveMachine(serverLevel, stack);
        if (machine == null) {
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "unreachable"), true);
            return InteractionResult.SUCCESS;
        }

        ChronosphereBlockEntity.RegionResult result = machine.addTrackedRegion(BoundingBox.fromCorners(first.get(), clicked));
        player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + switch (result) {
            case ADDED -> "added";
            case NOT_BOUND -> "not_bound";
            case OUT_OF_BOUNDS -> "out_of_bounds";
            case CLAIMED_ELSEWHERE -> "claimed_elsewhere";
        }), true);
        if (result == ChronosphereBlockEntity.RegionResult.ADDED) {
            level.playSound(null, clicked, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.PLAYERS, 1.0F, 1.0F);
            sendRegions(player, machine);
        }
        return InteractionResult.SUCCESS;
    }

    /** The Chronosphere stack is linked to, if it is loaded and in level's dimension. */
    @Nullable
    public static ChronosphereBlockEntity resolveMachine(ServerLevel level, ItemStack stack) {
        GlobalPos linked = getLinkedChronosphere(stack);
        if (linked == null || !linked.dimension().equals(level.dimension()) || !level.isLoaded(linked.pos())) return null;
        BlockEntity be = level.getBlockEntity(linked.pos());
        return be instanceof ChronosphereBlockEntity machine ? machine : null;
    }

    /**
     * Left-click-with-glue handler: removes every tracked region along the player's line of sight
     * out to block reach, or clears a pending first corner if nothing was hit.
     */
    public static void deleteRegionsAlongSight(ServerLevel level, ServerPlayer player, ItemStack stack) {
        ChronosphereBlockEntity machine = resolveMachine(level, stack);
        if (machine != null) {
            Vec3 origin = player.getEyePosition();
            Vec3 end = origin.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
            if (machine.removeTrackedRegionsAlongRay(origin, end) > 0) {
                player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "removed"), true);
                level.playSound(null, player.blockPosition(), SoundEvents.SLIME_BLOCK_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);
                sendRegions(player, machine);
                return;
            }
        }

        if (getPendingCorner(stack).isPresent()) {
            clearPending(stack);
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "selection_cleared"), true);
        }
    }

    /** Whether player is holding Temporal Glue in either hand. */
    public static boolean isHolding(Player player) {
        return player.getMainHandItem().getItem() instanceof TemporalGlueItem
                || player.getOffhandItem().getItem() instanceof TemporalGlueItem;
    }

    /** Pushes machine's tracked regions to player. */
    public static void sendRegions(ServerPlayer player, @Nullable ChronosphereBlockEntity machine) {
        List<BoundingBox> regions = List.of();
        if (machine != null && machine.getRepository() != null) {
            regions = List.copyOf(machine.getRepository().getTrackedRegions());
        }
        PacketDistributor.sendToPlayer(player,
                new GlueRegionSyncPacket(player.level().dimension().location(), regions, true));
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!isSelected || !level.isClientSide) return;
        if (level.getGameTime() % POLL_INTERVAL_TICKS != 0) return;

        PacketDistributor.sendToServer(GlueRegionRequestPacket.TRACKED);
    }

    /** The pending first corner stored on stack, if any. */
    public static Optional<BlockPos> getPendingCorner(ItemStack stack) {
        CompoundTag data = readData(stack);
        if (!data.contains(PENDING_X)) return Optional.empty();
        return Optional.of(new BlockPos(data.getInt(PENDING_X), data.getInt(PENDING_Y), data.getInt(PENDING_Z)));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos linked = getLinkedChronosphere(stack);
        if (linked != null) {
            tooltip.add(Component.translatable(MESSAGE_PREFIX + "tooltip_linked",
                    linked.pos().getX() + ", " + linked.pos().getY() + ", " + linked.pos().getZ()).withStyle(ChatFormatting.AQUA));
        }
        if (getPendingCorner(stack).isPresent()) {
            tooltip.add(Component.translatable(MESSAGE_PREFIX + "tooltip_pending").withStyle(ChatFormatting.YELLOW));
        }
        TooltipUtil.appendDescription(tooltip, MESSAGE_PREFIX + "tooltip", MESSAGE_PREFIX + "tooltip_remove");
    }

    private static CompoundTag readData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void clearPending(ItemStack stack) {
        CompoundTag data = readData(stack);
        data.remove(PENDING_X);
        data.remove(PENDING_Y);
        data.remove(PENDING_Z);
        if (data.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }
}
