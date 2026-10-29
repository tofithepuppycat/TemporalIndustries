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
     * Outcome of a {@link #jump(long)}: SUCCESS covers a jump that has started or a no-op; BUSY
     * means the history is being captured or rewritten; CHARGING means a jump to that commit is
     * already collecting the energy it could not find stored, and will run on its own.
     */
    enum JumpResult { SUCCESS, CHARGING, BUSY }

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
     * Every chunk a jump to targetCommitId could change, without resolving any of them, so a huge
     * claim can be budgeted.
     */
    java.util.Set<ChunkPos> previewAffectedChunks(long targetCommitId);

    /**
     * Target states of the positions in one chunk that a jump to targetCommitId would change right
     * now; resolve lazily so a huge claim can be budgeted.
     */
    Map<BlockPos, BlockState> previewChunkTargetStates(long targetCommitId, ChunkPos chunk);

    /**
     * Recorded state of every entity a jump to targetCommitId would restore; empty unless the
     * machine saves and loads entities.
     */
    Map<java.util.UUID, net.minecraft.nbt.CompoundTag> previewEntityStates(long targetCommitId);

    /** Whether jumps on this machine also save and load entities. */
    boolean isSaveEntities();

    /** Whether pos is glued, and so would be skipped by a jump (and by the ghost preview). */
    boolean isGluedPos(BlockPos pos);

    /**
     * Starts a jump to targetCommitId. The world is rewritten over the following ticks, nearest
     * the players first; requester, if any, is told when the jump has to charge first.
     */
    JumpResult jump(long targetCommitId, @org.jetbrains.annotations.Nullable java.util.UUID requester);

    default JumpResult jump(long targetCommitId) {
        return jump(targetCommitId, null);
    }

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
