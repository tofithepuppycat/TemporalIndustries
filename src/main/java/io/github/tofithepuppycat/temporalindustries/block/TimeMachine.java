package io.github.tofithepuppycat.temporalindustries.block;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.tofithepuppycat.temporalindustries.block.entity.TimeMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import io.github.tofithepuppycat.temporalindustries.Registration;
import com.mojang.serialization.MapCodec;

@SuppressWarnings("null")
public class TimeMachine extends BaseEntityBlock {
    private static final MapCodec<TimeMachine> CODEC = simpleCodec(TimeMachine::new);

    public TimeMachine(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState blockState) {
        return RenderShape.MODEL;
    }

    /**
     * A chunk's timeline is only meaningful relative to a single Time Machine's own rollback
     * history (see TemporalTimeline) — a second machine tracking the same chunk would silently
     * fight over which one's checkouts actually apply to it. Refusing to place (or survive) here
     * keeps that 1:1 relationship intact, the same way vanilla blocks refuse to place where they
     * can't structurally stand.
     */
    @Override
    public boolean canSurvive(@NotNull BlockState state, @NotNull LevelReader level, @NotNull BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            TemporalWorldData data = TemporalWorldData.get(serverLevel.getServer());
            if (data.isTracked(new ChunkPos(pos))) {
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
        if (blockEntity instanceof TimeMachineBlockEntity && player instanceof ServerPlayer) {
            TimeMachineBlockEntity timeMachineBlockEntity = (TimeMachineBlockEntity) blockEntity;
            ServerPlayer serverPlayer = (ServerPlayer) player;
            serverPlayer.openMenu(timeMachineBlockEntity, buf -> buf.writeBlockPos(blockPos));
        }

        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, Registration.TIME_MACHINE_BLOCK_ENTITY.get(), TimeMachineBlockEntity::tick);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos blockPos, @NotNull BlockState blockState) {
        return new TimeMachineBlockEntity(blockPos, blockState);
    }
}
