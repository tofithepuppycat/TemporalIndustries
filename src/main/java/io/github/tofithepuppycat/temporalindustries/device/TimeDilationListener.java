package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.item.TimeDilatorItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives the Time Dilator's effect: every server tick, re-ticks each position {@link
 * io.github.tofithepuppycat.temporalindustries.item.TimeDilatorItem} has marked, on top of the one
 * tick it already gets normally.
 */
public final class TimeDilationListener {
    private TimeDilationListener() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        TemporalWorldData data = TemporalWorldData.get(server);

        for (ServerLevel level : server.getAllLevels()) {
            var dimension = level.dimension().location();
            for (BlockPos pos : data.tickAcceleratedBlocks(dimension)) {
                applyExtraTicks(level, pos);
            }
        }
    }

    private static void applyExtraTicks(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        RandomSource random = level.getRandom();
        for (int i = 0; i < TimeDilatorItem.EXTRA_TICKS_PER_SERVER_TICK; i++) {
            if (!state.isRandomlyTicking()) break;
            state.randomTick(level, pos, random);
            state = level.getBlockState(pos);
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return;

        BlockEntityTicker<BlockEntity> ticker = tickerFor(level, state, blockEntity);
        if (ticker == null) return;

        for (int i = 0; i < TimeDilatorItem.EXTRA_TICKS_PER_SERVER_TICK; i++) {
            ticker.tick(level, pos, state, blockEntity);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity> BlockEntityTicker<T> tickerFor(ServerLevel level, BlockState state, T blockEntity) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) return null;
        BlockEntityType<T> type = (BlockEntityType<T>) blockEntity.getType();
        return entityBlock.getTicker(level, state, type);
    }
}
