package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;

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

    long getPlacedGameTime();
    long getSelectedGameTime();

    /** Jumps to targetGameTime, applying it to the live world immediately. */
    void jump(long targetGameTime);

    /** Updates selectedGameTime, optionally applying it to the live world. */
    void setSelectedGameTime(long targetGameTime, boolean applyToWorld);
}
