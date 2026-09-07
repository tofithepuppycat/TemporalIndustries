package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.timeline.ChunkTimelineSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Exposes a chunk's commit graph plus the shared jump/select controls, for whichever block entity
 * is currently showing {@link io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget}
 * in its GUI. Implemented by {@link ChronovaultBlockEntity} (always its own single chunk) and
 * {@link ChronosphereBlockEntity} (chunkPos null means the shared "All" view across every claimed
 * chunk). jump() always moves every chunk a provider controls together.
 */
public interface TimelineViewProvider {
    /** Outcome of a {@link #jump(long, long)}: SUCCESS covers an actual jump or a no-op;
     * INSUFFICIENT_ENERGY lets callers show a chat message instead of silently doing nothing. */
    enum JumpResult { SUCCESS, INSUFFICIENT_ENERGY }

    /** Chunks this provider can show a separate timeline tab for, home chunk first. Empty when
     * there's only one chunk (a Time Machine). */
    default List<ChunkPos> getViewableChunks() {
        return List.of();
    }

    /** Commits relevant to chunkPos in chronological order, or the shared view when chunkPos is null. */
    List<TemporalCommit> getChunkCommits(@Nullable ChunkPos chunkPos);

    /** commitId -> the id it locally forked from within chunkPos's (or the shared view's) history. */
    Map<Long, Long> getChunkLocalParents(@Nullable ChunkPos chunkPos);

    /** The commit chunkPos's (or the shared view's) live world currently reflects. */
    long getChunkHeadId(@Nullable ChunkPos chunkPos);

    /** Id of the commit nearest the current selectedGameTime. */
    default long getSelectedCommitId(@Nullable ChunkPos chunkPos) {
        return TemporalCommit.resolveNearest(getChunkCommits(chunkPos), getSelectedGameTime());
    }

    /** commitId -> energy cost of jumping there from the current head. For a Chronosphere this is
     * always the total cost across every claimed chunk, since jump() moves everything together. */
    Map<Long, Long> getChunkJumpCosts(@Nullable ChunkPos chunkPos);

    /** One commit-graph snapshot per chunk that should show the in-world ghost preview when
     * "Show Changes" is on. */
    List<ChunkTimelineSnapshot> getPreviewChunkSnapshots();

    long getPlacedGameTime();
    long getSelectedGameTime();

    /** Jumps to the commit at targetGameTime, applying it to the live world immediately. Prefers
     * targetCommitId as the exact checkout target since gameTime alone can't distinguish a branch
     * marker from the commit it forked from. Pass {@link TemporalCommit#NO_PREFERRED_COMMIT} to
     * resolve by gameTime alone. */
    JumpResult jump(long targetGameTime, long targetCommitId);

    /** Updates selectedGameTime, optionally applying it to the live world. */
    void setSelectedGameTime(long targetGameTime, boolean applyToWorld);
}
