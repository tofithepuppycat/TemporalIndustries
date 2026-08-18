package io.github.tofithepuppycat.temporalindustries.block;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.CrudeEntropyCondenserBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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

/** Lower, unpowered tier of {@link EntropyCondenser}: rather than absorbing orbs directly, it slowly
 * drains an Order/Chaos Cell placed in its input slot into its own tanks; see
 * {@link CrudeEntropyCondenserBlockEntity}. No facing/range needed since it has no absorb area. */
@SuppressWarnings("null")
public class CrudeEntropyCondenser extends BaseEntityBlock {
    private static final MapCodec<CrudeEntropyCondenser> CODEC = simpleCodec(CrudeEntropyCondenser::new);

    public CrudeEntropyCondenser(Properties properties) {
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

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, Registration.CRUDE_ENTROPY_CONDENSER_BLOCK_ENTITY.get(), CrudeEntropyCondenserBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new CrudeEntropyCondenserBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (level.getBlockEntity(pos) instanceof CrudeEntropyCondenserBlockEntity be && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(be, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }
}
