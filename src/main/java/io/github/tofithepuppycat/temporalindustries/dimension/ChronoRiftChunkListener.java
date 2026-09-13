package io.github.tofithepuppycat.temporalindustries.dimension;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.timeline.ChronoRiftFiller;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Queues a Chrono Rift chunk for filling the moment it loads, piggybacking on vanilla's own
 * chunk-loading/ticket system as the "fill as needed" trigger instead of a custom radius-scanning
 * tick loop. Filling itself is deferred to {@link #onServerTick} rather than run straight from
 * {@link #onChunkLoad}: {@link ChronoRiftFiller#fillChunk} writes real blocks and runs neighbor-
 * shape "settle" updates, which can force-load bordering chunks — doing that synchronously from
 * inside the Load event would re-enter it and cascade outward across the whole dimension in one
 * call, which was observed hanging the server. */
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

        for (int i = 0; i < FILLS_PER_TICK; i++) {
            ChunkPos pos = data.pollChronoRiftFill();
            if (pos == null) break;
            ChronoRiftFiller.fillChunk(server, riftLevel, pos, activeTime);
        }
    }
}
