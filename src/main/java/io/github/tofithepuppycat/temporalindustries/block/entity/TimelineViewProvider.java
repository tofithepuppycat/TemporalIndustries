package io.github.tofithepuppycat.temporalindustries.block.entity;

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
 * GUI. Implemented by {@link ChronovaultBlockEntity} (one claimed chunk) and
 * {@link ChronosphereBlockEntity} (up to 121).
 *
 * <p>Repo-scoped, not chunk-scoped: unlike the old per-dimension timeline there is exactly one
 * commit graph per machine, so the graph needs no chunk parameter. "Which chunks are claimed"
 * ({@link #getClaimedChunks()}) is a separate, still-per-chunk concern, used only for the claim
 * map and the in-world preview boundary.
 */
public interface TimelineViewProvider {
    /** Outcome of a {@link #jump(long)}: SUCCESS covers an actual jump or a no-op;
     * INSUFFICIENT_ENERGY lets callers show a chat message instead of silently doing nothing. */
    enum JumpResult { SUCCESS, INSUFFICIENT_ENERGY }

    /** Every commit in this machine's repository. Edges come from each commit's own
     * {@link RepoCommit#getParentId()} — there's no separate local-parent map any more. */
    List<RepoCommit> getRepositoryCommits();

    /** Branch name -> the commit it currently points to. */
    Map<String, Long> getBranchTips();

    /** The commit the live world currently reflects for this machine's claimed chunks. */
    long getHeadCommitId();

    /** The named branch currently checked out, or an empty string if this repo has no commits. */
    String getCurrentNamedBranch();

    /** commitId -> energy cost of jumping there from the current head. Only ever prices
     * selectedCommitId (pass -1 for none): pricing every node would be
     * O(nodes x claimedChunks x history) and stall the server tick on every GUI poll, so
     * un-selected nodes simply render without a jump-cost line. */
    Map<Long, Long> getJumpCosts(long selectedCommitId);

    /** Chunks this machine has claimed, home chunk first. Used for the claim map and to outline
     * the preview boundary in-world — NOT for the commit graph, which is repo-wide. */
    List<ChunkPos> getClaimedChunks();

    long getPlacedGameTime();
    long getSelectedGameTime();

    /** Per affected chunk, that chunk's resolved target block states if the player jumped to
     * targetCommitId right now. Read-only; shares its resolution logic with the real jump so the
     * ghost preview can't diverge from it. */
    Map<ChunkPos, Map<BlockPos, BlockState>> previewJumpTargetStates(long targetCommitId);

    /** Whether pos is glued, and so would be skipped by a jump (and by the ghost preview). */
    boolean isGluedPos(BlockPos pos);

    /** Jumps to targetCommitId, applying it to the live world immediately. The target time is read
     * off the commit itself, so no separate gameTime parameter is needed. */
    JumpResult jump(long targetCommitId);

    /** Points selectedGameTime at commitId's recorded time, optionally applying it to the world. */
    void setSelectedCommit(long commitId, boolean applyToWorld);

    /** Deletes branchName and the history unique to it, provided it isn't the branch currently
     * checked out.
     * @return true if the branch was deleted */
    boolean deleteBranch(String branchName);
}
