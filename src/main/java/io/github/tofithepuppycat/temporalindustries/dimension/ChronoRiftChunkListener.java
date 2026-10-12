package io.github.tofithepuppycat.temporalindustries.dimension;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.timeline.ChronoRiftFiller;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Queues a Chrono Rift chunk for filling the moment it loads, piggybacking on vanilla's own chunk-
 * loading/ticket system as the "fill as needed" trigger instead of a custom radius-scanning tick
 * loop.
 */
public final class ChronoRiftChunkListener {
    /** Chunks filled per server tick — bounds how much reconstruction work one tick can do. */
    private static final int FILLS_PER_TICK = 4;

    private ChronoRiftChunkListener() {}

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel riftLevel) || riftLevel.dimension() != ChronoRiftDimension.LEVEL_KEY) {
            return;
        }

        TemporalWorldData data = TemporalWorldData.get(riftLevel.getServer());
        data.markChronoRiftChunkLoaded(event.getChunk().getPos());
        if (data.getChronoRiftActiveTime() == TemporalWorldData.NO_CHRONO_RIFT_SESSION) return;

        data.enqueueChronoRiftFill(event.getChunk().getPos());
    }

    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel riftLevel) || riftLevel.dimension() != ChronoRiftDimension.LEVEL_KEY) {
            return;
        }

        TemporalWorldData.get(riftLevel.getServer()).markChronoRiftChunkUnloaded(event.getChunk().getPos());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel riftLevel = ChronoRiftDimension.getLevel(server);
        if (riftLevel == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        long activeTime = data.getChronoRiftActiveTime();
        if (activeTime == TemporalWorldData.NO_CHRONO_RIFT_SESSION) return;

        // Deferred across the whole tick's batch, same reasoning as the rift travel prefill:
        // settling a chunk right after it's filled can reach into a sibling this tick hasn't gotten
        // to yet, which still looks like unfilled void and would wrongly destroy blocks at the
        // boundary.
        List<BlockPos> deferredSettle = new ArrayList<>();
        for (int i = 0; i < FILLS_PER_TICK; i++) {
            ChunkPos pos = data.pollChronoRiftFill();
            if (pos == null) break;
            ChronoRiftFiller.fillChunk(server, riftLevel, pos, activeTime, deferredSettle);
        }
        TemporalTimeline.settleBlocks(riftLevel, deferredSettle);
    }
}
