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
 * the latest repository commit, across every repository, that recorded the chunk at or before that
 * gameTime — or leaves it void (left "untracked") where no such commit exists.
 */
public final class ChronoRiftFiller {
    private ChronoRiftFiller() {}

    /** No-ops if pos was already filled for targetGameTime this session. */
    public static void fillChunk(MinecraftServer server, ServerLevel riftLevel, ChunkPos pos, long targetGameTime) {
        fillChunk(server, riftLevel, pos, targetGameTime, null);
    }

    /**
     * Same as {@link #fillChunk(MinecraftServer, ServerLevel, ChunkPos, long)}, but when
     * deferredSettle is non-null this chunk's neighbor-shape settle pass is collected into it
     * instead of running immediately — for a caller filling several chunks together (e.g. the
     * rift's prefill radius) to settle them all only once every chunk in the batch has real blocks
     * in place, so a chunk's settle pass never runs while a sibling in the same batch is still
     * void.
     */
    public static void fillChunk(MinecraftServer server, ServerLevel riftLevel, ChunkPos pos, long targetGameTime, @Nullable List<BlockPos> deferredSettle) {
        TemporalWorldData data = TemporalWorldData.get(server);
        Long filledAt = data.getChronoRiftChunkFilledTime(pos);
        if (filledAt != null && filledAt == targetGameTime) return;

        TemporalRepository sourceRepo = null;
        RepoCommit source = null;
        for (TemporalRepository repo : data.getRepositoriesIn(Level.OVERWORLD.location())) {
            RepoCommit candidate = repo.latestCommitTouching(pos, targetGameTime);
            if (candidate == null) continue;
            if (source == null || candidate.getGameTime() > source.getGameTime()
                    || (candidate.getGameTime() == source.getGameTime() && candidate.getId() > source.getId())) {
                sourceRepo = repo;
                source = candidate;
            }
        }

        // A chunk painted for an earlier moment is wiped first so nothing from it survives into the
        // new one.
        List<BlockPos> settle = deferredSettle != null ? deferredSettle : new ArrayList<>();
        if (data.isChronoRiftChunkTracked(pos)) clearChunkToVoid(riftLevel, pos, settle);
        boolean tracked = source != null && sourceRepo.paintChunkAt(source.getId(), pos, riftLevel, settle);
        if (deferredSettle == null) TemporalRepository.settleBlocks(riftLevel, settle);

        data.markChronoRiftChunkFilled(pos, targetGameTime);
        data.markChronoRiftChunkTracked(pos, tracked);
        data.setDirty();

        PacketDistributor.sendToPlayersInDimension(riftLevel,
                new ChronoRiftChunkTrackedSyncPacket(Map.of(pos.toLong(), tracked)));
    }

    /**
     * Resets every non-air block in pos back to air, skipping empty sections outright so a chunk
     * that was never painted (the common case) costs nothing beyond the section scan.
     */
    private static void clearChunkToVoid(ServerLevel level, ChunkPos pos, List<BlockPos> settle) {
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

        settle.addAll(changed);
    }
}
