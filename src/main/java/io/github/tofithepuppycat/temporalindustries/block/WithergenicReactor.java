package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.WithergenicReactorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.capture.CapturedMob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

/**
 * Controller of the Withergenic Reactor multiblock, a late-game Chaos generator; see {@link
 * WithergenicReactorBlockEntity}.
 */
@SuppressWarnings("null")
public class WithergenicReactor extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    // Whether the surrounding shell and output hatch are in place - see WithergenicReactorBlockEntity#checkStructure.
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    private static final MapCodec<WithergenicReactor> CODEC = simpleCodec(WithergenicReactor::new);
    private static final DustParticleOptions MISSING_PARTICLE = new DustParticleOptions(new Vector3f(1.0F, 0.35F, 0.35F), 0.6F);
    private static final double EDGE_PARTICLE_SPACING = 0.2;

    public WithergenicReactor(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FORMED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FORMED);
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
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                             @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;

        CapturedMob captured = stack.get(Registration.CAPTURED_MOB.get());
        if (captured != null && level.getBlockEntity(pos) instanceof WithergenicReactorBlockEntity be) {
            be.capture(captured.entityTypeId(), captured.customName().orElse(null));
        }
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                          @NotNull BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock() && level.getBlockEntity(pos) instanceof WithergenicReactorBlockEntity be) {
            be.release();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, Registration.WITHERGENIC_REACTOR_BLOCK_ENTITY.get(), WithergenicReactorBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new WithergenicReactorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (level.getBlockEntity(pos) instanceof WithergenicReactorBlockEntity be && player instanceof ServerPlayer serverPlayer) {
            return interact(level, pos, be, serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Opens the GUI if the structure is formed, otherwise auto-fills missing frames from the
     * player's inventory or highlights what is still missing.
     */
    public static InteractionResult interact(Level level, BlockPos pos, WithergenicReactorBlockEntity be, ServerPlayer player) {
        if (be.checkStructure()) {
            player.openMenu(be, buf -> buf.writeBlockPos(pos));
            return InteractionResult.CONSUME;
        }

        MachineFrame.fillFromInventory(level, be.findMissingFrames(), player);
        if (be.checkStructure()) return InteractionResult.CONSUME;

        List<BlockPos> missing = be.findMissingFrames();
        highlight((ServerLevel) level, missing, player);
        if (!be.hasHatch()) {
            highlight((ServerLevel) level, List.of(be.hatchPosition()), player);
            player.displayClientMessage(Component.translatable("block.temporalindustries.withergenic_reactor.missing_hatch"), true);
        } else {
            player.displayClientMessage(Component.translatable("block.temporalindustries.withergenic_reactor.missing_frame", missing.size()), true);
        }
        return InteractionResult.CONSUME;
    }

    private static void highlight(ServerLevel level, List<BlockPos> positions, ServerPlayer player) {
        for (BlockPos pos : positions) {
            for (Vector3f point : BoxEdgeParticles.outline(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, EDGE_PARTICLE_SPACING)) {
                level.sendParticles(MISSING_PARTICLE, point.x(), point.y(), point.z(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }
}
