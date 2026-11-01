package io.github.tofithepuppycat.temporalindustries.client.timeline;

import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
import io.github.tofithepuppycat.temporalindustries.timeline.JumpCostBreakdown;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
    /** Recorded state of every entity a jump to selectedCommitId would restore. */
    private static List<CompoundTag> previewEntities = new ArrayList<>();
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
    public static final int GHOST_BLOCK_RENDER_LIMIT = 50_000;

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
        invalidateEntries();
        previewChunks = new ArrayList<>();
        previewEntities = new ArrayList<>();
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
        previewEntities = new ArrayList<>();
        invalidateEntries();
    }

    /**
     * Selects the changes a commit would record rather than a commit, which the server resolves
     * against the head even before any commit graph has arrived.
     */
    public static void selectPendingChanges() {
        selectedCommitId = TimelineViewProvider.PENDING_CHANGES;
        previewTargetStates = new HashMap<>();
        previewEntities = new ArrayList<>();
        invalidateEntries();
    }

    public static boolean isShowChangesEnabled() {
        return showChangesEnabled;
    }

    public static void enableShowChanges() {
        showChangesEnabled = true;
    }

    public static void disableShowChanges() {
        showChangesEnabled = false;
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
                                        List<ChunkPos> serverPreviewChunks, List<CompoundTag> serverPreviewEntities,
                                        long serverPreviewVersion) {
        activeMachinePos = machinePos;
        placedGameTime = Math.max(0L, placed);
        currentGameTime = Math.max(placedGameTime, current);
        commits = new ArrayList<>(serverCommits);
        branchTips = new HashMap<>(serverBranchTips);
        currentBranch = serverCurrentBranch;
        headCommitId = serverHeadCommitId;
        jumpCosts = new HashMap<>(serverJumpCosts);
        previewTargetStates = serverPreviewTargetStates;
        invalidateEntries();
        previewChunks = new ArrayList<>(serverPreviewChunks);
        previewEntities = new ArrayList<>(serverPreviewEntities);
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
        previewEntities = new ArrayList<>();
        invalidateEntries();
    }

    public static boolean isBranchTip(long commitId) {
        return branchTips.containsValue(commitId);
    }

    /**
     * Whether the selected commit is deletable: it must not sit on the current head's ancestry
     * (head and root included), which is the line the world currently reflects.
     */
    public static boolean isSelectedCommitDeletable() {
        if (!hasSelection()) return false;

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

    /**
     * Recorded entities the active preview would restore. The list is replaced, never mutated, on
     * every change, so its identity tells a cache when to rebuild.
     */
    public static List<CompoundTag> getPreviewEntities() {
        return previewEntities;
    }

    /** How often the diff against the live world is redone; in between, the last result is reused. */
    private static final long ENTRY_REFRESH_TICKS = 10L;
    private static List<ProjectionEntry> cachedEntries;
    private static long cachedEntriesTick = Long.MIN_VALUE;

    private static void invalidateEntries() {
        cachedEntries = null;
    }

    /**
     * Diffs the server's resolved target states against the live world, at most once every few
     * ticks; the returned list is stable between refreshes so callers can cache work against it.
     */
    /** What stands at pos once the preview's jump has run: the previewed state if it changes, else what is there now. */
    public static BlockState getTargetStateAt(Level level, BlockPos pos) {
        BlockState previewed = previewTargetStates.get(pos);
        return previewed != null ? previewed : level.getBlockState(pos);
    }

    public static List<ProjectionEntry> getProjectionEntries(Level level) {
        if (!hasActivePreview()) return List.of();

        long now = level.getGameTime();
        if (cachedEntries != null && now - cachedEntriesTick < ENTRY_REFRESH_TICKS && now >= cachedEntriesTick) {
            return cachedEntries;
        }
        cachedEntries = computeEntries(level);
        cachedEntriesTick = now;
        return cachedEntries;
    }

    private static List<ProjectionEntry> computeEntries(Level level) {
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
