package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side mirror of which Chrono Rift chunks the server considers "tracked" (have valid
 * reconstructed history), fed by {@link
 * io.github.tofithepuppycat.temporalindustries.network.ChronoRiftChunkTrackedSyncPacket}.
 */
public final class ChronoRiftClientState {
    private static final Set<Long> trackedChunks = new HashSet<>();

    private ChronoRiftClientState() {}

    public static void applyUpdates(Map<Long, Boolean> updates) {
        for (Map.Entry<Long, Boolean> entry : updates.entrySet()) {
            if (entry.getValue()) trackedChunks.add(entry.getKey());
            else trackedChunks.remove(entry.getKey());
        }
    }

    public static boolean isTracked(ChunkPos pos) {
        return trackedChunks.contains(pos.toLong());
    }

    /**
     * Unconditionally drops all tracked chunks; called on world/server disconnect so a previous
     * world's tracked rift chunks can't bleed into the next one joined (the rift dimension key is
     * the same ResourceLocation across every world).
     */
    public static void clearAll() {
        trackedChunks.clear();
    }
}
