package io.github.tofithepuppycat.temporalindustries.client.timeline;

import io.github.tofithepuppycat.temporalindustries.timeline.ChunkTimelineSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** Client-side view of the active Time Machine's chunk history: caches the last commit graph
 * fetched from the server, tracks the player's selected commit, and computes the block-diff
 * preview for {@link TimelineProjectionRenderer}. */
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
    /** commitId -> energy cost of jumping there from the chunk's current head, as last reported by the server. */
    private static Map<Long, Long> jumpCosts = new HashMap<>();
    /** One commit-graph snapshot per chunk the ghost preview should cover: the graph's own chunk
     * for a Time Machine, every claimed chunk for a Chronosphere. */
    private static List<ChunkTimelineSnapshot> previewChunkSnapshots = new ArrayList<>();
    /** Every glued region in the active machine's dimension; positions inside these are excluded
     * from the ghost preview since a jump skips them entirely. Synced directly rather than reusing
     * GlueSelectionClientState, which only stays fresh while a Temporal Glue item is held. */
    private static List<BoundingBox> gluedRegions = new ArrayList<>();
    /** Last-synced previewVersion fingerprint, echoed back so the server can skip replying when nothing changed. */
    private static long previewVersion = Long.MIN_VALUE;
    /** Whether the in-world block-diff preview is toggled on via the "Show Changes" button. */
    private static boolean showChangesEnabled = false;
    /** null when browsing the shared "All" view; otherwise the single claimed chunk whose own tab
     * is open, so the boundary renderer can outline it even inside a larger claim. */
    private static ChunkPos selectedViewChunk = null;

    private TimelineProjectionManager() {}

    public static void setActiveMachine(BlockPos machinePos) {
        activeMachinePos = machinePos;
    }

    /** The machine the ghost preview is currently tracking, or null if none; used by
     * {@link TimelineProjectionPoller} to keep polling after the machine's GUI has closed. */
    public static BlockPos getActiveMachinePos() {
        return activeMachinePos;
    }

    public static void clearActiveMachine(BlockPos machinePos) {
        if (activeMachinePos != null && activeMachinePos.equals(machinePos)) {
            clearAll();
        }
    }

    /** Unconditionally drops all client-side state. Used when the BlockPos itself stops being
     * meaningful, e.g. leaving a world, so a stale activeMachinePos can't match new coordinates. */
    public static void clearAll() {
        activeMachinePos = null;
        commits = new ArrayList<>();
        localParents = new HashMap<>();
        headCommitId = -1L;
        selectedCommitId = -1L;
        jumpCosts = new HashMap<>();
        previewChunkSnapshots = new ArrayList<>();
        gluedRegions = new ArrayList<>();
        previewVersion = Long.MIN_VALUE;
        showChangesEnabled = false;
        selectedViewChunk = null;
    }

    /** Tells the boundary renderer which claimed chunk's own tab is open. Pass null for the shared "All" view. */
    public static void setSelectedViewChunk(ChunkPos chunk) {
        selectedViewChunk = chunk;
    }

    public static ChunkPos getSelectedViewChunk() {
        return selectedViewChunk;
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
                                        List<ChunkTimelineSnapshot> serverPreviewChunkSnapshots,
                                        List<BoundingBox> serverGluedRegions, long serverPreviewVersion) {
        activeMachinePos = machinePos;
        placedGameTime = Math.max(0L, placed);
        currentGameTime = Math.max(placedGameTime, current);
        commits = new ArrayList<>(serverCommits);
        localParents = new HashMap<>(serverLocalParents);
        headCommitId = serverHeadCommitId;
        jumpCosts = new HashMap<>(serverJumpCosts);
        previewChunkSnapshots = new ArrayList<>(serverPreviewChunkSnapshots);
        gluedRegions = new ArrayList<>(serverGluedRegions);
        previewVersion = serverPreviewVersion;
    }

    private static TemporalCommit findCommit(long id) {
        for (TemporalCommit c : commits) {
            if (c.getId() == id) return c;
        }
        return null;
    }

    /** Selects exactly commitId, rather than re-deriving it from game time (ambiguous for a
     * branch marker, which shares its gameTime with the commit it forked from). */
    public static void setSelectedCommit(long commitId) {
        TemporalCommit commit = findCommit(commitId);
        if (commit == null) return;
        selectedCommitId = commitId;
        selectedGameTime = clampSelected(commit.getGameTime());
    }

    /** Whether the currently selected node is a BRANCH commit that can be safely deleted — i.e. the
     * chunk's current head isn't itself, or a descendant of it, on the branch's forked-off lineage. */
    public static boolean isSelectedBranchDeletable() {
        TemporalCommit selected = findCommit(selectedCommitId);
        if (selected == null || selected.getType() != TemporalCommit.Type.BRANCH) return false;

        // Walk headCommitId's ancestry; if it passes through selectedCommitId, the branch is either
        // the current head itself or an ancestor of it, meaning it's currently checked out.
        long current = headCommitId;
        Set<Long> visited = new HashSet<>();
        while (current >= 0 && visited.add(current)) {
            if (current == selectedCommitId) return false;
            current = localParents.getOrDefault(current, -1L);
        }
        return true;
    }

    public static List<TemporalCommit> getCommits() { return commits; }
    /** commitId -> the id it locally forked from within this chunk's history. */
    public static Map<Long, Long> getLocalParents() { return localParents; }
    public static long getSelectedCommitId() { return selectedCommitId; }
    /** The commit this chunk's live world currently reflects, as opposed to the browsing selection. */
    public static long getHeadCommitId() { return headCommitId; }
    public static long getPreviewVersion() { return previewVersion; }
    /** Energy cost of jumping to commitId from the chunk's current head, or empty if unknown. */
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

    /** Every chunk the active preview covers, used to outline the claim's outer boundary in-world
     * while "Show Changes" is on, even where no individual block happens to be changing. */
    public static List<ChunkPos> getPreviewChunks() {
        List<ChunkPos> chunks = new ArrayList<>(previewChunkSnapshots.size());
        for (ChunkTimelineSnapshot snapshot : previewChunkSnapshots) chunks.add(snapshot.chunkPos());
        return chunks;
    }

    /** Computes which blocks differ from their live world state at selectedGameTime, across every
     * chunk in previewChunkSnapshots, using the same {@link TemporalTimeline#walkDeltas} the
     * server's real jump uses. Known gap: a jump crossing a re-snapshot boundary needs a full
     * chunk baseline the client never receives, so the preview under-reports changes there. */
    public static List<ProjectionEntry> getProjectionEntries(Level level) {
        if (!hasActivePreview()) return List.of();

        List<ProjectionEntry> entries = new ArrayList<>();
        for (ChunkTimelineSnapshot snapshot : previewChunkSnapshots) {
            appendProjectionEntries(level, snapshot, entries);
        }
        return entries;
    }

    /** Prefers the exact selected commit id when this chunk's commit list contains it, falling
     * back to gameTime-based resolution only for chunks that don't. resolveNearest alone is
     * ambiguous once a BRANCH marker shares the exact gameTime of the commit it forked from. */
    private static long resolveTargetCommit(List<TemporalCommit> chunkCommits) {
        for (TemporalCommit c : chunkCommits) {
            if (c.getId() == selectedCommitId) return selectedCommitId;
        }
        return TemporalCommit.resolveNearest(chunkCommits, selectedGameTime);
    }

    private static void appendProjectionEntries(Level level, ChunkTimelineSnapshot snapshot, List<ProjectionEntry> out) {
        List<TemporalCommit> chunkCommits = snapshot.commits();
        if (chunkCommits.isEmpty()) return;

        long targetCommitId = resolveTargetCommit(chunkCommits);
        TemporalTimeline.DeltaWalkResult walk = TemporalTimeline.walkDeltas(
                chunkCommits, snapshot.localParents(), snapshot.chunkPos(), snapshot.headId(), targetCommitId);

        for (Map.Entry<BlockPos, BlockState> entry : walk.states().entrySet()) {
            BlockPos pos = entry.getKey();
            if (isGlued(pos)) continue; // a jump skips glued positions entirely — see isGlued's doc.
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

    /** True when pos sits inside any glued region synced for the active machine's dimension,
     * meaning a jump would leave it untouched. Mirrors TemporalWorldData#isGlued server-side. */
    private static boolean isGlued(BlockPos pos) {
        for (BoundingBox region : gluedRegions) {
            if (region.isInside(pos)) return true;
        }
        return false;
    }

    private static long clampSelected(long selected) {
        long min = placedGameTime;
        long max = Math.max(min, currentGameTime);
        return Math.max(min, Math.min(max, selected));
    }
}
