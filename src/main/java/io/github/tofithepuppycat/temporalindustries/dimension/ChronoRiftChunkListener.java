package io.github.tofithepuppycat.temporalindustries.dimension;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.timeline.ChronoRiftFiller;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Fills a Chrono Rift chunk with reconstructed history (or a glitch placeholder) the moment it
 * loads, piggybacking on vanilla's own chunk-loading/ticket system as the "fill as needed" trigger
 * instead of a custom radius-scanning tick loop. */
public final class ChronoRiftChunkListener {
    private ChronoRiftChunkListener() {}

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel riftLevel) || riftLevel.dimension() != ChronoRiftDimension.LEVEL_KEY) {
            return;
        }

        long activeTime = TemporalWorldData.get(riftLevel.getServer()).getChronoRiftActiveTime();
        if (activeTime == TemporalWorldData.NO_CHRONO_RIFT_SESSION) return;

        ChronoRiftFiller.fillChunk(riftLevel.getServer(), riftLevel, event.getChunk().getPos(), activeTime);
    }
}
