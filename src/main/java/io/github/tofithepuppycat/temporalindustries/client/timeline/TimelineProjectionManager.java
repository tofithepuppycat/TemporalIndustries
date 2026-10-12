package io.github.tofithepuppycat.temporalindustries.client.timeline;

import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
import io.github.tofithepuppycat.temporalindustries.timeline.JumpCostBreakdown;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side view of the active machine's repository: caches the last commit graph fetched from
 * the server, tracks the player's selected commit, and holds the server-resolved ghost preview for
 * {@link TimelineProjectionRenderer}.
 */
@SuppressWarnings("null")
public final class TimelineProjectionManager {
    public enum ProjectionType { ADD, REMOVE, CHANGE }

    public static final class ProjectionEntry {
        private final BlockPos pos;
        private final BlockState currentState;
        private final BlockState targetState;
        private final ProjectionType type;

        public ProjectionEntry(BlockPos pos, BlockState currentState, BlockState targetState, ProjectionType type) {
            this.pos = pos;
            this.currentState = currentState;
            this.targetState = targetState;
            this.type = type;
        }

        public BlockPos getPos() { return pos; }
        public BlockState getCurrentState() { return currentState; }
        public BlockState getTargetState() { return targetState; }
        public ProjectionType getType() { return type; }
    }

    private static BlockPos activeMachinePos;
    private static long placedGameTime;
    private static long selectedGameTime;
    private static long currentGameTime;
    private static List<CommitNode> commits = new ArrayList<>();
    /** Branch name -> its tip commit id, as last reported by the server. */
    private static Map<String, Long> branchTips = new HashMap<>();
    private static String currentBranch = "";
    private static long headCommitId = -1L;
    private static long selectedCommitId = -1L;
    /** commitId -> energy cost of jumping there from the current head, as last reported. */
    private static Map<Long, JumpCostBreakdown> jumpCosts = new HashMap<>();
    /**
     * Server-resolved target state of every block a jump to selectedCommitId would touch, already
     * glue-filtered.
     */
    private static Map<BlockPos, BlockState> previewTargetStates = new HashMap<>();
    /**
     * Every chunk the machine has claimed, used to outline the claim's outer boundary in-world.
     */
    private static List<ChunkPos> previewChunks = new ArrayList<>();
    /**
     * Last-synced previewVersion fingerprint, echoed back so the server can skip replying when
     * nothing changed.
     */
    private static long previewVersion = Long.MIN_VALUE;
    /** Whether the in-world block-diff preview is toggled on via the "Show Changes" button. */
    private static boolean showChangesEnabled = false;
    /**
     * Whether "Show Changes" renders actual translucent block models instead of colored decal
     * markers.
     */
    private static boolean ghostBlockModeEnabled = false;
    /**
     * Hard cap on how many actual block models the ghost-block preview mode will render per frame.
     */
    public static final int GHOST_BLOCK_RENDER_LIMIT = 800;

    private TimelineProjectionManager() {}

    public static void setActiveMachine(BlockPos machinePos) {
        activeMachinePos = machinePos;
    }

    /**
     * The machine the ghost preview is currently tracking, or null if none; used by {@link
     * TimelineProjectionPoller} to keep polling after the machine's GUI has closed.
     */
    public static BlockPos getActiveMachinePos() {
        return activeMachinePos;
    }

    public static void clearActiveMachine(BlockPos machinePos) {
        if (activeMachinePos != null && activeMachinePos.equals(machinePos)) {
            clearAll();
        }
    }

    /** Unconditionally drops all client-side state. */
    public static void clearAll() {
        activeMachinePos = null;
        commits = new ArrayList<>();
        branchTips = new HashMap<>();
        currentBranch = "";
        headCommitId = -1L;
        selectedCommitId = -1L;
        jumpCosts = new HashMap<>();
        previewTargetStates = new HashMap<>();
        previewChunks = new ArrayList<>();
        previewVersion = Long.MIN_VALUE;
        showChangesEnabled = false;
    }

    public static boolean hasSelection() {
        return selectedCommitId != -1L;
    }

    /** Deselects the current node, e.g. when the user clicks blank space in the graph. */
    public static void clearSelectedCommit() {
        selectedCommitId = -1L;
        previewTargetStates = new HashMap<>();
    }

    public static boolean isShowChangesEnabled() {
        return showChangesEnabled;
    }

    public static void toggleShowChanges() {
        showChangesEnabled = !showChangesEnabled;
    }

    public static boolean isGhostBlockModeEnabled() { return ghostBlockModeEnabled; }

    public static void toggleGhostBlockMode() {
        ghostBlockModeEnabled = !ghostBlockModeEnabled;
    }

    public static boolean hasActivePreview() {
        return activeMachinePos != null && hasSelection() && showChangesEnabled;
    }

    /** Refreshes the commit graph, branch refs and ghost preview from a fresh server snapshot. */
    public static void updateFromServer(BlockPos machinePos, long placed, long current,
                                        List<CommitNode> serverCommits, Map<String, Long> serverBranchTips,
                                        String serverCurrentBranch, long serverHeadCommitId,
                                        Map<Long, JumpCostBreakdown> serverJumpCosts,
                                        Map<BlockPos, BlockState> serverPreviewTargetStates,
                                        List<ChunkPos> serverPreviewChunks, long serverPreviewVersion) {
        activeMachinePos = machinePos;
        placedGameTime = Math.max(0L, placed);
        currentGameTime = Math.max(placedGameTime, current);
        commits = new ArrayList<>(serverCommits);
        branchTips = new HashMap<>(serverBranchTips);
        currentBranch = serverCurrentBranch;
        headCommitId = serverHeadCommitId;
        jumpCosts = new HashMap<>(serverJumpCosts);
        previewTargetStates = new HashMap<>(serverPreviewTargetStates);
        previewChunks = new ArrayList<>(serverPreviewChunks);
        previewVersion = serverPreviewVersion;
    }

    private static CommitNode findCommit(long id) {
        for (CommitNode c : commits) {
            if (c.id() == id) return c;
        }
        return null;
    }

    /** Selects exactly commitId. */
    public static void setSelectedCommit(long commitId) {
        CommitNode commit = findCommit(commitId);
        if (commit == null) return;
        selectedCommitId = commitId;
        selectedGameTime = clampSelected(commit.gameTime());
        previewTargetStates = new HashMap<>();
    }

    public static boolean isBranchTip(long commitId) {
        return branchTips.containsValue(commitId);
    }

    /** The branch whose tip is the selected commit, or null when the selection isn't a tip. */
    public static String getSelectedBranchName() {
        if (!hasSelection()) return null;
        for (Map.Entry<String, Long> entry : branchTips.entrySet()) {
            if (entry.getValue() == selectedCommitId) return entry.getKey();
        }
        return null;
    }

    /**
     * Whether the selected node is a deletable branch: it has to be a branch tip, and that tip must
     * not sit on the current head's ancestry (which would mean it's the line the world currently
     * reflects).
     */
    public static boolean isSelectedBranchDeletable() {
        if (getSelectedBranchName() == null) return false;

        Map<Long, Long> parents = new HashMap<>();
        for (CommitNode c : commits) parents.put(c.id(), c.parentId());

        long current = headCommitId;
        Set<Long> visited = new HashSet<>();
        while (current >= 0 && visited.add(current)) {
            if (current == selectedCommitId) return false;
            current = parents.getOrDefault(current, -1L);
        }
        return true;
    }

    public static List<CommitNode> getCommits() { return commits; }
    public static Map<String, Long> getBranchTips() { return branchTips; }
    public static String getCurrentBranch() { return currentBranch; }
    public static long getSelectedCommitId() { return selectedCommitId; }
    /** The commit the live world currently reflects, as opposed to the browsing selection. */
    public static long getHeadCommitId() { return headCommitId; }
    public static long getPreviewVersion() { return previewVersion; }
    /** Energy cost of jumping to commitId from the current head, or empty if unknown. */
    public static Optional<JumpCostBreakdown> getJumpCost(long commitId) {
        return Optional.ofNullable(jumpCosts.get(commitId));
    }
    public static long getPlacedGameTime() { return placedGameTime; }
    public static long getCurrentGameTime() { return currentGameTime; }
    public static long getSelectedGameTime() { return selectedGameTime; }

    public static void setCurrentGameTime(long current) {
        currentGameTime = Math.max(placedGameTime, current);
        selectedGameTime = clampSelected(selectedGameTime);
    }

    /**
     * Every chunk the active preview covers, used to outline the claim's outer boundary in-world
     * while "Show Changes" is on, even where no individual block happens to be changing.
     */
    public static List<ChunkPos> getPreviewChunks() {
        return previewChunks;
    }

    /** Diffs the server's resolved target states against the live world right now. */
    public static List<ProjectionEntry> getProjectionEntries(Level level) {
        if (!hasActivePreview()) return List.of();

        List<ProjectionEntry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : previewTargetStates.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState targetState = entry.getValue();
            BlockState currentState = level.getBlockState(pos);
            if (currentState.equals(targetState)) continue;

            ProjectionType type;
            if (targetState.isAir()) {
                type = ProjectionType.REMOVE;
            } else if (currentState.isAir()) {
                type = ProjectionType.ADD;
            } else {
                type = ProjectionType.CHANGE;
            }
            entries.add(new ProjectionEntry(pos, currentState, targetState, type));
        }
        return entries;
    }

    private static long clampSelected(long selected) {
        long min = placedGameTime;
        long max = Math.max(min, currentGameTime);
        return Math.max(min, Math.min(max, selected));
    }
}
