package io.github.tofithepuppycat.temporalindustries.timeline;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRiftChunkTrackedSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Paints one Chrono Rift chunk with the Overworld's reconstructed history at a chosen moment —
 * whatever commit each chunk's own timeline had at, or before, that gameTime — or leaves it void
 * (left "untracked") where no such commit exists. Server-only.
 */
public final class ChronoRiftFiller {
    private ChronoRiftFiller() {}

    /** No-ops if pos was already filled for targetGameTime this session. */
    public static void fillChunk(MinecraftServer server, ServerLevel riftLevel, ChunkPos pos, long targetGameTime) {
        fillChunk(server, riftLevel, pos, targetGameTime, null);
    }

    /** Same as {@link #fillChunk(MinecraftServer, ServerLevel, ChunkPos, long)}, but when
     * deferredSettle is non-null this chunk's neighbor-shape settle pass is collected into it instead
     * of running immediately — for a caller filling several chunks together (e.g. the rift's prefill
     * radius) to settle them all only once every chunk in the batch has real blocks in place, so a
     * chunk's settle pass never runs while a sibling in the same batch is still void. */
    public static void fillChunk(MinecraftServer server, ServerLevel riftLevel, ChunkPos pos, long targetGameTime, @Nullable List<BlockPos> deferredSettle) {
        TemporalWorldData data = TemporalWorldData.get(server);
        Long filledAt = data.getChronoRiftChunkFilledTime(pos);
        if (filledAt != null && filledAt == targetGameTime) return;

        TemporalTimeline overworldTimeline = data.getTimeline(Level.OVERWORLD.location());
        long floorCommitId = overworldTimeline == null ? -1L : overworldTimeline.resolveFloorCommit(pos, targetGameTime);
        boolean tracked = overworldTimeline != null && floorCommitId != -1L;

        if (tracked) {
            // fromCommitId = -1 tells applyChunkAtTime the rift's blank chunk has no live lineage of
            // its own, so it replays the *entire* chain from the chunk's nearest SNAPSHOT ancestor
            // forward, fully painting the chunk from nothing rather than diffing against live state.
            overworldTimeline.applyChunkAtTime(pos, targetGameTime, -1L, riftLevel, floorCommitId, p -> false, deferredSettle);
        } else if (data.isChronoRiftChunkTracked(pos)) {
            // This chunk was painted for a previous session's time but has no history at the newly
            // selected time — clear it back to void, or the old session's blocks would linger forever.
            clearChunkToVoid(riftLevel, pos, deferredSettle);
        }

        data.markChronoRiftChunkFilled(pos, targetGameTime);
        data.markChronoRiftChunkTracked(pos, tracked);
        data.setDirty();

        PacketDistributor.sendToPlayersInDimension(riftLevel,
                new ChronoRiftChunkTrackedSyncPacket(Map.of(pos.toLong(), tracked)));
    }

    /** Resets every non-air block in pos back to air, skipping empty sections outright so a chunk
     * that was never painted (the common case) costs nothing beyond the section scan. */
    private static void clearChunkToVoid(ServerLevel level, ChunkPos pos, @Nullable List<BlockPos> deferredSettle) {
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = level.getMinSection();

        List<BlockPos> changed = new ArrayList<>();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            int baseY = (minSectionY + i) << 4;
            for (int dy = 0; dy < 16; dy++) {
                for (int dz = 0; dz < 16; dz++) {
                    for (int dx = 0; dx < 16; dx++) {
                        BlockPos worldPos = new BlockPos(pos.getMinBlockX() + dx, baseY + dy, pos.getMinBlockZ() + dz);
                        if (!level.getBlockState(worldPos).isAir()) {
                            level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                            changed.add(worldPos);
                        }
                    }
                }
            }
        }

        if (deferredSettle != null) {
            deferredSettle.addAll(changed);
        } else {
            TemporalTimeline.settleBlocks(level, changed);
        }
    }
}
