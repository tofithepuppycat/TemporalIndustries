package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.timeline.JumpCostBreakdown;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * Exposes one machine's repository — a single commit tree, shared across every chunk the machine
 * has claimed — plus the jump/select controls, for whichever block entity is currently showing
 * {@link io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget} in its
 * GUI.
 */
public interface TimelineViewProvider {
    /**
     * Outcome of a {@link #jump(long)}: SUCCESS covers an actual jump or a no-op;
     * CHARGING means the stored energy could not cover the cost, so the machine is collecting it and
     * will jump on its own once it has enough.
     */
    enum JumpResult { SUCCESS, CHARGING }

    /** Every commit in this machine's repository. */
    List<RepoCommit> getRepositoryCommits();

    /** Branch name -> the commit it currently points to. */
    Map<String, Long> getBranchTips();

    /** The commit the live world currently reflects for this machine's claimed chunks. */
    long getHeadCommitId();

    /** The named branch currently checked out, or an empty string if this repo has no commits. */
    String getCurrentNamedBranch();

    /** commitId -> energy cost of jumping there from the current head. */
    Map<Long, JumpCostBreakdown> getJumpCosts(long selectedCommitId);

    /** Chunks this machine has claimed, home chunk first. */
    List<ChunkPos> getClaimedChunks();

    long getPlacedGameTime();
    long getSelectedGameTime();

    /**
     * Per affected chunk, that chunk's resolved target block states if the player jumped to
     * targetCommitId right now.
     */
    Map<ChunkPos, Map<BlockPos, BlockState>> previewJumpTargetStates(long targetCommitId);

    /** The chunk set of {@link #previewJumpTargetStates} without resolving any of them. */
    java.util.Set<ChunkPos> previewAffectedChunks(long targetCommitId);

    /** One chunk's resolved target states; resolve lazily so a huge claim can be budgeted. */
    Map<BlockPos, BlockState> previewChunkTargetStates(long targetCommitId, ChunkPos chunk);

    /** Whether pos is glued, and so would be skipped by a jump (and by the ghost preview). */
    boolean isGluedPos(BlockPos pos);

    /** Jumps to targetCommitId, applying it to the live world immediately. */
    JumpResult jump(long targetCommitId);

    /**
     * Points selectedGameTime at commitId's recorded time, optionally applying it to the world.
     */
    void setSelectedCommit(long commitId, boolean applyToWorld);

    /**
     * Deletes commitId and every commit descended from it, provided it isn't on the current head's
     * ancestry.
     */
    boolean deleteCommit(long commitId);
}
