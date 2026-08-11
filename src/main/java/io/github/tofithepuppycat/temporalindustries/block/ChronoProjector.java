package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronoProjectorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.device.ChronoRecording;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRecorderItem;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
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
 * Consumes energy to endlessly replay whatever Chrono Recorder is inserted into it (see
 * {@link ChronoProjectorBlockEntity}), rendered client-side as a translucent cyan ghost. Interaction
 * mirrors a jukebox: right-click with a recorded Chrono Recorder to insert it, right-click empty
 * handed to take it back out.
 */
@SuppressWarnings("null")
public class ChronoProjector extends BaseEntityBlock {
    private static final MapCodec<ChronoProjector> CODEC = simpleCodec(ChronoProjector::new);

    public ChronoProjector(Properties properties) {
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
        if (!(stack.getItem() instanceof ChronoRecorderItem)) {
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
            player.displayClientMessage(Component.translatable("block.temporalindustries.chrono_projector.no_recording"), true);
            return ItemInteractionResult.FAIL;
        }

        ItemStack toInsert = stack.copyWithCount(1);
        stack.shrink(1);
        projector.insertRecorder(toInsert);
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.2F);
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
