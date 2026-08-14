package io.github.tofithepuppycat.temporalindustries.client.timeline;

import io.github.tofithepuppycat.temporalindustries.timeline.BlockChangeDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkTimelineSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Client-side view of the active Time Machine's chunk history: caches the last commit graph
 * fetched from the server, tracks the player's selected commit, and computes the block-diff
 * preview for {@link io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionRenderer}. */
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
    private static List<TemporalCommit> commits = new ArrayList<>();
    private static Map<Long, Long> localParents = new HashMap<>();
    private static long headCommitId = -1L;
    private static long selectedCommitId = -1L;
    /** commitId -> energy cost of jumping there from the chunk's current head, as last reported
     * by the server (see TimeMachineBlockEntity#getChunkJumpCosts). */
    private static Map<Long, Long> jumpCosts = new HashMap<>();
    /** One commit-graph snapshot per chunk the ghost preview should cover — just the graph's own
     * chunk for a Time Machine, but every claimed chunk for a Chronosphere (see
     * TimelineViewProvider#getPreviewChunkSnapshots()), since a jump moves all of them together. */
    private static List<ChunkTimelineSnapshot> previewChunkSnapshots = new ArrayList<>();
    /** Whether the in-world block-diff preview is toggled on via the "Show Changes" button. */
    private static boolean showChangesEnabled = false;

    private TimelineProjectionManager() {}

    public static void setActiveMachine(BlockPos machinePos) {
        activeMachinePos = machinePos;
    }

    public static void clearActiveMachine(BlockPos machinePos) {
        if (activeMachinePos != null && activeMachinePos.equals(machinePos)) {
            clearAll();
        }
    }

    /** Unconditionally drops all client-side state, regardless of which machine (if any) is
     * active. Used when the BlockPos itself stops being meaningful — e.g. leaving a world/server,
     * where a stale activeMachinePos would otherwise keep matching the same coordinates in
     * whatever world is joined next and project a ghost preview from data that no longer applies. */
    public static void clearAll() {
        activeMachinePos = null;
        commits = new ArrayList<>();
        localParents = new HashMap<>();
        headCommitId = -1L;
        selectedCommitId = -1L;
        jumpCosts = new HashMap<>();
        previewChunkSnapshots = new ArrayList<>();
        showChangesEnabled = false;
    }

    public static boolean hasSelection() {
        return selectedCommitId != -1L;
    }

    /** Deselects the current node, e.g. when the user clicks blank space in the graph. */
    public static void clearSelectedCommit() {
        selectedCommitId = -1L;
    }

    public static boolean isShowChangesEnabled() {
        return showChangesEnabled;
    }

    public static void toggleShowChanges() {
        showChangesEnabled = !showChangesEnabled;
    }

    public static boolean hasActivePreview() {
        return activeMachinePos != null && hasSelection() && showChangesEnabled;
    }

    /** Refreshes commits/head from a fresh server snapshot. Does not touch the current selection. */
    public static void updateFromServer(BlockPos machinePos, long placed, long current,
                                        List<TemporalCommit> serverCommits, Map<Long, Long> serverLocalParents,
                                        long serverHeadCommitId, Map<Long, Long> serverJumpCosts,
                                        List<ChunkTimelineSnapshot> serverPreviewChunkSnapshots) {
        activeMachinePos = machinePos;
        placedGameTime = Math.max(0L, placed);
        currentGameTime = Math.max(placedGameTime, current);
        commits = new ArrayList<>(serverCommits);
        localParents = new HashMap<>(serverLocalParents);
        headCommitId = serverHeadCommitId;
        jumpCosts = new HashMap<>(serverJumpCosts);
        previewChunkSnapshots = new ArrayList<>(serverPreviewChunkSnapshots);
    }

    private static TemporalCommit findCommit(long id) {
        for (TemporalCommit c : commits) {
            if (c.getId() == id) return c;
        }
        return null;
    }

    /** Selects exactly commitId, rather than re-deriving it from a game time (ambiguous for a
     * branch marker, which shares its exact gameTime with the commit it forked from). */
    public static void setSelectedCommit(long commitId) {
        TemporalCommit commit = findCommit(commitId);
        if (commit == null) return;
        selectedCommitId = commitId;
        selectedGameTime = clampSelected(commit.getGameTime());
    }

    public static List<TemporalCommit> getCommits() { return commits; }
    /** commitId -> the id it locally forked from within this chunk's history (see TemporalTimeline). */
    public static Map<Long, Long> getLocalParents() { return localParents; }
    public static long getSelectedCommitId() { return selectedCommitId; }
    /** The commit this chunk's live world currently reflects (as opposed to the browsing selection). */
    public static long getHeadCommitId() { return headCommitId; }
    /** Energy cost of jumping to commitId from the chunk's current head, or empty if unknown
     * (e.g. stale client state right after switching machines). */
    public static OptionalLong getJumpCost(long commitId) {
        Long cost = jumpCosts.get(commitId);
        return cost == null ? OptionalLong.empty() : OptionalLong.of(cost);
    }
    public static long getPlacedGameTime() { return placedGameTime; }
    public static long getCurrentGameTime() { return currentGameTime; }
    public static long getSelectedGameTime() { return selectedGameTime; }

    public static void setCurrentGameTime(long current) {
        currentGameTime = Math.max(placedGameTime, current);
        selectedGameTime = clampSelected(selectedGameTime);
    }

    /** Every chunk the active preview covers — the graph's own chunk for a Time Machine, every
     * claimed chunk for a Chronosphere. Used to outline the claim's outer boundary in-world while
     * "Show Changes" is on, so the player can see the extent a jump would actually touch even
     * where no individual block happens to be changing. */
    public static List<ChunkPos> getPreviewChunks() {
        List<ChunkPos> chunks = new ArrayList<>(previewChunkSnapshots.size());
        for (ChunkTimelineSnapshot snapshot : previewChunkSnapshots) chunks.add(snapshot.chunkPos());
        return chunks;
    }

    /** Computes which blocks differ from their live world state at selectedGameTime, across every
     * chunk in previewChunkSnapshots (not just whichever chunk the graph is displaying) — mirroring
     * {@link io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline#applyChunkAtTime}
     * per chunk so the preview matches what Jump will actually do everywhere it does it. */
    public static List<ProjectionEntry> getProjectionEntries(Level level) {
        if (!hasActivePreview()) return List.of();

        List<ProjectionEntry> entries = new ArrayList<>();
        for (ChunkTimelineSnapshot snapshot : previewChunkSnapshots) {
            appendProjectionEntries(level, snapshot, entries);
        }
        return entries;
    }

    private static void appendProjectionEntries(Level level, ChunkTimelineSnapshot snapshot, List<ProjectionEntry> out) {
        List<TemporalCommit> chunkCommits = snapshot.commits();
        if (chunkCommits.isEmpty()) return;

        Map<Long, Long> chunkLocalParents = snapshot.localParents();
        long targetCommitId = TemporalCommit.resolveNearest(chunkCommits, selectedGameTime);

        List<TemporalCommit> liveChain = TemporalCommit.ancestryChain(chunkCommits, chunkLocalParents, snapshot.headId());
        List<TemporalCommit> targetChain = TemporalCommit.ancestryChain(chunkCommits, chunkLocalParents, targetCommitId);

        int commonPrefixLen = 0;
        int maxCommon = Math.min(liveChain.size(), targetChain.size());
        while (commonPrefixLen < maxCommon
                && liveChain.get(commonPrefixLen).getId() == targetChain.get(commonPrefixLen).getId()) {
            commonPrefixLen++;
        }

        Map<BlockPos, BlockState> desiredStates = new HashMap<>();

        for (int i = liveChain.size() - 1; i >= commonPrefixLen; i--) {
            for (ChunkDelta cd : liveChain.get(i).getChunkDeltas()) {
                if (!cd.getChunkPos().equals(snapshot.chunkPos())) continue;
                for (BlockChangeDelta change : cd.getBlockChanges()) {
                    desiredStates.put(change.getPos(), change.getPreviousState());
                }
            }
        }

        for (int i = commonPrefixLen; i < targetChain.size(); i++) {
            for (ChunkDelta cd : targetChain.get(i).getChunkDeltas()) {
                if (!cd.getChunkPos().equals(snapshot.chunkPos())) continue;
                for (BlockChangeDelta change : cd.getBlockChanges()) {
                    desiredStates.put(change.getPos(), change.getNewState());
                }
            }
        }

        for (Map.Entry<BlockPos, BlockState> entry : desiredStates.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState targetState = entry.getValue();
            BlockState currentState = level.getBlockState(pos);

            if (!currentState.equals(targetState)) {
                ProjectionType type;
                if (targetState.isAir()) {
                    type = ProjectionType.REMOVE;
                } else if (currentState.isAir()) {
                    type = ProjectionType.ADD;
                } else {
                    type = ProjectionType.CHANGE;
                }
                out.add(new ProjectionEntry(pos, currentState, targetState, type));
            }
        }
    }

    private static long clampSelected(long selected) {
        long min = placedGameTime;
        long max = Math.max(min, currentGameTime);
        return Math.max(min, Math.min(max, selected));
    }
}
