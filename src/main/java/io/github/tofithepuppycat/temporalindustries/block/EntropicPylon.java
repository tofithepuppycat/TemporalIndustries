package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropicPylonBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Routes liquid Order/Chaos between blocks marked via {@link
 * io.github.tofithepuppycat.temporalindustries.item.EntropicPylonItem}; see {@link
 * EntropicPylonBlockEntity} for the transfer logic.
 */
@SuppressWarnings("null")
public class EntropicPylon extends BaseEntityBlock {
    // Whether machine frames extend the pylon downward - see EntropicPylonBlockEntity#checkStructure.
    public static final BooleanProperty EXTENDED = BooleanProperty.create("extended");

    private static final VoxelShape CAP_SHAPE = Shapes.or(
            Block.box(5, 0, 5, 11, 12, 11),
            Block.box(4, 12, 4, 12, 13, 12),
            Block.box(6, 13, 6, 10, 16, 10));

    private static final VoxelShape STANDALONE_SHAPE = Shapes.or(
            Block.box(0, 0, 0, 16, 2, 16),
            Block.box(3, 2, 3, 13, 4, 13),
            Block.box(5, 4, 5, 11, 12, 11),
            Block.box(4, 12, 4, 12, 13, 12),
            Block.box(6, 13, 6, 10, 16, 10));

    private static final MapCodec<EntropicPylon> CODEC = simpleCodec(EntropicPylon::new);

    /** Entropy type this pylon is restricted to, or {@code null} for the dual pylon. */
    @Nullable
    private final EntropyType filter;
    private final Supplier<BlockEntityType<EntropicPylonBlockEntity>> blockEntityType;

    public EntropicPylon(Properties properties) {
        this(properties, null, Registration.ENTROPIC_PYLON_BLOCK_ENTITY);
    }

    protected EntropicPylon(Properties properties, @Nullable EntropyType filter,
                             Supplier<BlockEntityType<EntropicPylonBlockEntity>> blockEntityType) {
        super(properties);
        this.filter = filter;
        this.blockEntityType = blockEntityType;
        registerDefaultState(stateDefinition.any().setValue(EXTENDED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(EXTENDED);
    }

    @Override
    protected VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return state.getValue(EXTENDED) ? CAP_SHAPE : STANDALONE_SHAPE;
    }

    @Override
    protected void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            MachineFrame.releasePylonFrames(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, blockEntityType.get(), EntropicPylonBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new EntropicPylonBlockEntity(blockEntityType.get(), pos, state, filter);
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof EntropicPylonBlockEntity be && player instanceof ServerPlayer serverPlayer) {
            return be.onFrameInteract(level, pos, serverPlayer);
        }
        return InteractionResult.CONSUME;
    }
}
