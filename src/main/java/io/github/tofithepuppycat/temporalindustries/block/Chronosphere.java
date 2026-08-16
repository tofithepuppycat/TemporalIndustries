package io.github.tofithepuppycat.temporalindustries.block;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.serialization.MapCodec;

/**
 * The multi-chunk-tier time machine: like {@link TimeMachine}, its home chunk can't overlap
 * another machine's tracked chunk (see canSurvive), but it can go on to additionally claim up to
 * a 5x5 area of chunks around itself (see {@link ChronosphereBlockEntity}), all moved together by
 * one jump and paid from one shared energy pool.
 *
 * <p>Purely visual: FACING tracks which way the model's animated front panel (see the block model's
 * "north" texture) points, exactly like a furnace — it has no bearing on chunk claiming/jumping,
 * which is always centred on the block's own position regardless of orientation.
 */
@SuppressWarnings("null")
public class Chronosphere extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final MapCodec<Chronosphere> CODEC = simpleCodec(Chronosphere::new);

    public Chronosphere(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState blockState) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean canSurvive(@NotNull BlockState state, @NotNull LevelReader level, @NotNull BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            TemporalWorldData data = TemporalWorldData.get(serverLevel.getServer());
            if (data.isTracked(serverLevel.dimension().location(), new ChunkPos(pos))) {
                return false;
            }
        }
        return super.canSurvive(state, level, pos);
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState blockState, @NotNull Level level, @NotNull BlockPos blockPos, @NotNull Player player, @NotNull BlockHitResult blockHitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity instanceof ChronosphereBlockEntity && player instanceof ServerPlayer) {
            ChronosphereBlockEntity chronosphereBlockEntity = (ChronosphereBlockEntity) blockEntity;
            ServerPlayer serverPlayer = (ServerPlayer) player;
            serverPlayer.openMenu(chronosphereBlockEntity, buf -> buf.writeBlockPos(blockPos));
        }

        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, Registration.CHRONOSPHERE_BLOCK_ENTITY.get(), ChronosphereBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos blockPos, @NotNull BlockState blockState) {
        return new ChronosphereBlockEntity(blockPos, blockState);
    }
}
