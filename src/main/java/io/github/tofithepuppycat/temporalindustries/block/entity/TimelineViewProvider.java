package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.timeline.ChunkTimelineSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Exposes one chunk's commit graph plus the shared jump/select controls, for whichever block
 * entity is currently showing {@link io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget}
 * in its GUI. Implemented by {@link TimeMachineBlockEntity} (its own single tracked chunk) and
 * {@link ChronosphereBlockEntity} (its home chunk — jump() still moves every chunk it has
 * claimed, not just the one whose graph is displayed).
 */
public interface TimelineViewProvider {
    /** Every commit relevant to the displayed chunk — commits touching it plus its own branch
     * markers — in chronological order. */
    List<TemporalCommit> getChunkCommits();

    /** commitId -> the id it locally forked from within the displayed chunk's own history. */
    Map<Long, Long> getChunkLocalParents();

    /** The commit the displayed chunk's live world currently reflects. */
    long getChunkHeadId();

    /** The id of the commit whose gameTime is closest to the current selectedGameTime. */
    long getSelectedCommitId();

    /** commitId -> energy cost of jumping there from the current head, for every commit relevant
     * to the displayed chunk. For a Chronosphere this is the total cost across every claimed
     * chunk, not just the displayed one. */
    Map<Long, Long> getChunkJumpCosts();

    /** One commit-graph snapshot per chunk that should show the in-world ghost preview when
     * "Show Changes" is on — for a Time Machine just its own chunk (the same data as
     * getChunkCommits() etc., wrapped with its ChunkPos); for a Chronosphere, every chunk
     * currently claimed, since jump() moves all of them together. */
    List<ChunkTimelineSnapshot> getPreviewChunkSnapshots();

    long getPlacedGameTime();
    long getSelectedGameTime();

    /** Jumps to targetGameTime, applying it to the live world immediately. */
    void jump(long targetGameTime);

    /** Updates selectedGameTime, optionally applying it to the live world. */
    void setSelectedGameTime(long targetGameTime, boolean applyToWorld);

    // -------------------------------------------------------------------------
    // Multi-chunk view — a Time Machine only ever has its own single chunk, so the defaults below
    // just delegate to the no-arg methods above; ChronosphereBlockEntity is the only implementer
    // that overrides them, to let its GUI show either one specific claimed chunk's own graph or a
    // shared view merging every claimed chunk's history together.

    /** Every chunk this provider can show a separate timeline tab for, home/primary chunk first.
     * Empty (rather than the single implicit chunk getChunkCommits() etc. already cover) when
     * there's nothing to choose between — a screen only needs a chunk selector when this has more
     * than one entry. */
    default List<ChunkPos> getViewableChunks() {
        return List.of();
    }

    /** Commits for one specific chunk from {@link #getViewableChunks()}, or — when chunkPos is
     * null — the shared view: only the commits every viewable chunk has in common (an
     * intersection, not a union; see ChronosphereBlockEntity#sharedCommits for why). */
    default List<TemporalCommit> getChunkCommits(@Nullable ChunkPos chunkPos) {
        return getChunkCommits();
    }

    /** Local-parent map for chunkPos (or the shared view when null) — see {@link #getChunkCommits(ChunkPos)}. */
    default Map<Long, Long> getChunkLocalParents(@Nullable ChunkPos chunkPos) {
        return getChunkLocalParents();
    }

    /** The commit chunkPos's (or, when null, the primary/home chunk's) live world currently reflects. */
    default long getChunkHeadId(@Nullable ChunkPos chunkPos) {
        return getChunkHeadId();
    }

    /** The id of the commit nearest the current selectedGameTime, within chunkPos's (or the shared)
     * commit list. */
    default long getSelectedCommitId(@Nullable ChunkPos chunkPos) {
        return TemporalCommit.resolveNearest(getChunkCommits(chunkPos), getSelectedGameTime());
    }

    /** Jump costs for chunkPos's (or the shared view's) commit list — for a Chronosphere this is
     * the same total-cost-across-every-claimed-chunk value regardless of which tab is showing,
     * since Jump always moves everything together. */
    default Map<Long, Long> getChunkJumpCosts(@Nullable ChunkPos chunkPos) {
        return getChunkJumpCosts();
    }
}
