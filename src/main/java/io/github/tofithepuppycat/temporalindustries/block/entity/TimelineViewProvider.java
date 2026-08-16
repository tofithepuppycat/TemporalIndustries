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
 * in its GUI. Implemented by {@link TimeMachineBlockEntity} (always its own single chunk — every
 * method below ignores the chunkPos argument) and {@link ChronosphereBlockEntity} (chunkPos null
 * means the shared "All" view across every claimed chunk; otherwise one specific claimed chunk's
 * own graph). jump() always moves every chunk a provider controls together, regardless of which
 * one is currently being displayed.
 */
public interface TimelineViewProvider {
    /** The outcome of a {@link #jump(long, long)} — SUCCESS covers both an actual jump and a no-op
     * (nothing to do, or too far from the world to matter); INSUFFICIENT_ENERGY lets a caller with
     * a player to notify (see RollbackChunkPacket) show a chat message instead of the jump just
     * silently doing nothing. */
    enum JumpResult { SUCCESS, INSUFFICIENT_ENERGY }

    /** Every chunk this provider can show a separate timeline tab for, home/primary chunk first.
     * Empty when there's only one chunk (a Time Machine) — a screen only needs a chunk selector
     * when this has more than one entry. */
    default List<ChunkPos> getViewableChunks() {
        return List.of();
    }

    /** Every commit relevant to chunkPos — commits touching it plus its own branch markers — in
     * chronological order; or, when chunkPos is null, the shared view (see this interface's class
     * doc). */
    List<TemporalCommit> getChunkCommits(@Nullable ChunkPos chunkPos);

    /** commitId -> the id it locally forked from within chunkPos's (or the shared view's) own history. */
    Map<Long, Long> getChunkLocalParents(@Nullable ChunkPos chunkPos);

    /** The commit chunkPos's (or the shared view's) live world currently reflects. */
    long getChunkHeadId(@Nullable ChunkPos chunkPos);

    /** The id of the commit nearest the current selectedGameTime, within chunkPos's (or the shared)
     * commit list. */
    default long getSelectedCommitId(@Nullable ChunkPos chunkPos) {
        return TemporalCommit.resolveNearest(getChunkCommits(chunkPos), getSelectedGameTime());
    }

    /** commitId -> energy cost of jumping there from the current head, for every commit relevant
     * to chunkPos (or the shared view). For a Chronosphere this is always the total cost across
     * every claimed chunk, regardless of which tab is showing, since jump() moves everything
     * together. */
    Map<Long, Long> getChunkJumpCosts(@Nullable ChunkPos chunkPos);

    /** One commit-graph snapshot per chunk that should show the in-world ghost preview when
     * "Show Changes" is on — for a Time Machine just its own chunk, for a Chronosphere every chunk
     * currently claimed, since jump() moves all of them together. */
    List<ChunkTimelineSnapshot> getPreviewChunkSnapshots();

    long getPlacedGameTime();
    long getSelectedGameTime();

    /** Jumps to the commit whose gameTime is targetGameTime, applying it to the live world
     * immediately. Prefers targetCommitId as the exact checkout target (wherever it's actually one
     * of the relevant chunk's own commits) instead of re-deriving it from gameTime alone — a branch
     * marker always shares its exact gameTime with the commit it forked from, so gameTime alone
     * can't tell two different branches' same-tick commits apart. Pass
     * {@link TemporalCommit#NO_PREFERRED_COMMIT} to always resolve by gameTime alone. */
    JumpResult jump(long targetGameTime, long targetCommitId);

    /** Updates selectedGameTime, optionally applying it to the live world. */
    void setSelectedGameTime(long targetGameTime, boolean applyToWorld);
}
