package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.SchrodingersBoxBlockEntity;
import io.github.tofithepuppycat.temporalindustries.capture.CapturedMob;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Early-game generator block: undirected, its only state is whether a mob is currently trapped
 * inside (see {@link SchrodingersBoxBlockEntity}), which also drives a faint glow. Filled by
 * {@link io.github.tofithepuppycat.temporalindustries.item.SchrodingersBoxItem#interactLivingEntity}
 * before placement; emptied by an empty-handed right-click or by breaking the block, either of which
 * releases the mob back into the world rather than deleting it.
 */
@SuppressWarnings("null")
public class SchrodingersBox extends BaseEntityBlock {
    public static final BooleanProperty OCCUPIED = BlockStateProperties.OCCUPIED;

    private static final MapCodec<SchrodingersBox> CODEC = simpleCodec(SchrodingersBox::new);

    public SchrodingersBox(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OCCUPIED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OCCUPIED);
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                             @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;

        CapturedMob captured = stack.get(Registration.CAPTURED_MOB.get());
        if (captured != null && level.getBlockEntity(pos) instanceof SchrodingersBoxBlockEntity blockEntity) {
            blockEntity.capture(captured.entityTypeId(), captured.customName().orElse(null));
            level.setBlock(pos, state.setValue(OCCUPIED, true), 3);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                                @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!state.getValue(OCCUPIED)) return InteractionResult.PASS;

        if (level.getBlockEntity(pos) instanceof SchrodingersBoxBlockEntity blockEntity) {
            blockEntity.release();
            level.setBlock(pos, state.setValue(OCCUPIED, false), 3);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                          @NotNull BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock() && level.getBlockEntity(pos) instanceof SchrodingersBoxBlockEntity blockEntity) {
            blockEntity.release();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, Registration.SCHRODINGERS_BOX_BLOCK_ENTITY.get(), SchrodingersBoxBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new SchrodingersBoxBlockEntity(pos, state);
    }
}
