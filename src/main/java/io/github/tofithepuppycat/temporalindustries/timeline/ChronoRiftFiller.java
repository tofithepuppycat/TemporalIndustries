package io.github.tofithepuppycat.temporalindustries.timeline;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Paints one Chrono Rift chunk with the Overworld's reconstructed history at a chosen moment —
 * whatever commit each chunk's own timeline had at, or before, that gameTime — or a sparse
 * {@link io.github.tofithepuppycat.temporalindustries.block.TemporalGlitchBlock} placeholder where
 * no such commit exists. Server-only.
 */
public final class ChronoRiftFiller {
    private static final int GLITCH_MIN_Y = 60;

    private ChronoRiftFiller() {}

    /** No-ops if pos was already filled for targetGameTime this session. */
    public static void fillChunk(MinecraftServer server, ServerLevel riftLevel, ChunkPos pos, long targetGameTime) {
        TemporalWorldData data = TemporalWorldData.get(server);
        Long filledAt = data.getChronoRiftChunkFilledTime(pos);
        if (filledAt != null && filledAt == targetGameTime) return;

        TemporalTimeline overworldTimeline = data.getTimeline(Level.OVERWORLD.location());
        long floorCommitId = overworldTimeline == null ? -1L : overworldTimeline.resolveFloorCommit(pos, targetGameTime);

        if (overworldTimeline == null || floorCommitId == -1L) {
            glitchFillChunk(riftLevel, pos);
        } else {
            // fromCommitId = -1 tells applyChunkAtTime the rift's blank chunk has no live lineage of
            // its own, so it replays the *entire* chain from the chunk's nearest SNAPSHOT ancestor
            // forward, fully painting the chunk from nothing rather than diffing against live state.
            overworldTimeline.applyChunkAtTime(pos, targetGameTime, -1L, riftLevel, floorCommitId, p -> false);
        }

        data.markChronoRiftChunkFilled(pos, targetGameTime);
        data.setDirty();
    }

    /** Deterministic sparse "shattered fragments" fill for chunks with no valid history at the
     * requested moment: a hashed subset of columns get 1-3 stacked glitch blocks, the rest stays void. */
    private static void glitchFillChunk(ServerLevel riftLevel, ChunkPos pos) {
        BlockState glitch = Registration.TEMPORAL_GLITCH_BLOCK.get().defaultBlockState();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int worldX = pos.getMinBlockX() + dx;
                int worldZ = pos.getMinBlockZ() + dz;
                long h = hash(worldX, worldZ);
                if ((h & 0x3) != 0) continue; // ~1 in 4 columns gets fragments

                int height = 1 + (int) (h >>> 2 & 0x3); // 1-3 blocks tall
                int baseY = GLITCH_MIN_Y + (int) (h >>> 4 & 0x7) - 4; // small vertical jitter
                for (int dy = 0; dy < height; dy++) {
                    riftLevel.setBlock(new BlockPos(worldX, baseY + dy, worldZ), glitch, 2);
                }
            }
        }
    }

    private static long hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return h & Long.MAX_VALUE;
    }
}
