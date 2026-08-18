package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronoProjectorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.device.ChronoRecording;
import io.github.tofithepuppycat.temporalindustries.item.EchoRecordItem;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Consumes energy to endlessly replay whatever Echo Record is inserted into it (see
 * {@link ChronoProjectorBlockEntity}), rendered client-side as a translucent ghost (purple by
 * default; right-click with a dye to recolor it). Interaction otherwise mirrors a jukebox:
 * right-click with a recorded Echo Record to insert it, right-click empty handed to take it
 * back out.
 */
@SuppressWarnings("null")
public class EchoProjector extends BaseEntityBlock {
    private static final MapCodec<EchoProjector> CODEC = simpleCodec(EchoProjector::new);

    public EchoProjector(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, @NotNull BlockState state, @NotNull Level level,
                                               @NotNull BlockPos pos, @NotNull Player player,
                                               @NotNull InteractionHand hand, @NotNull BlockHitResult hitResult) {
        if (stack.getItem() instanceof DyeItem dye) {
            return dyeGhost(dye, level, pos, player, stack);
        }

        if (!(stack.getItem() instanceof EchoRecordItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }

        if (!(level.getBlockEntity(pos) instanceof ChronoProjectorBlockEntity projector)
                || !projector.getStoredRecorder().isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!ChronoRecording.hasSavedRecording(stack)) {
            player.displayClientMessage(Component.translatable("block.temporalindustries.echo_projector.no_recording"), true);
            return ItemInteractionResult.FAIL;
        }

        ItemStack toInsert = stack.copyWithCount(1);
        stack.shrink(1);
        projector.insertRecorder(toInsert);
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.2F);
        return ItemInteractionResult.CONSUME;
    }

    /** Right-clicking with a dye recolors the ghost render rather than doing the usual jukebox-style
     * insert, consuming one dye in survival like dyeing a sign or leather armor. */
    private ItemInteractionResult dyeGhost(DyeItem dye, Level level, BlockPos pos, Player player, ItemStack stack) {
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ChronoProjectorBlockEntity projector)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        projector.setGhostTint(dye.getDyeColor().getFireworkColor());
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                                @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (level.getBlockEntity(pos) instanceof ChronoProjectorBlockEntity projector
                && !projector.getStoredRecorder().isEmpty()) {
            ItemStack removed = projector.removeRecorder();
            if (!player.getInventory().add(removed)) {
                player.drop(removed, false);
            }
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0F, 0.9F);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                             @NotNull BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof ChronoProjectorBlockEntity projector) {
                ItemStack stored = projector.getStoredRecorder();
                if (!stored.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stored);
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, Registration.CHRONO_PROJECTOR_BLOCK_ENTITY.get(), ChronoProjectorBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new ChronoProjectorBlockEntity(pos, state);
    }
}
