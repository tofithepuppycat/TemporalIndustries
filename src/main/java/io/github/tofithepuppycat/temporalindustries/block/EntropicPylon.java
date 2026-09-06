package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropicPylonBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Routes liquid Order/Chaos between blocks the player marked ahead of time by right-clicking with
 * {@link io.github.tofithepuppycat.temporalindustries.item.EntropicPylonItem} in hand - see
 * {@link EntropicPylonBlockEntity} for the actual transfer. Like {@link LootGenerator}, only runs
 * once completed by a single {@link MachineFrame} directly above it. */
@SuppressWarnings("null")
public class EntropicPylon extends BaseEntityBlock {
    // Drives whether the pylon is actually routing entropy - see EntropicPylonBlockEntity#checkStructure.
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    private static final MapCodec<EntropicPylon> CODEC = simpleCodec(EntropicPylon::new);

    /** Which entropy type this pylon is restricted to, or {@code null} for the dual pylon - handed
     * straight to {@link EntropicPylonBlockEntity} on construction. */
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
        registerDefaultState(stateDefinition.any().setValue(FORMED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FORMED);
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
