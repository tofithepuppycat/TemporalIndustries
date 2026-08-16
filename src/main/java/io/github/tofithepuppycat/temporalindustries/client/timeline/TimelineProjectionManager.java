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
    /** Every glued region in the active machine's dimension, as of the last sync — positions inside
     * these are excluded from the ghost preview below, since a jump skips them entirely (mirrors
     * TemporalTimeline's isGlued predicate server-side). Synced directly rather than reusing
     * GlueSelectionClientState, which only stays fresh while a Temporal Glue item is actually held. */
    private static List<BoundingBox> gluedRegions = new ArrayList<>();
    /** Last-synced previewVersion fingerprint (see TimelinePreviewSyncPacket), echoed back on the
     * next request so the server can skip replying when nothing preview-relevant changed. See
     * TimelinePreviewRequestPacket#handle for what this covers beyond headCommitId. */
    private static long previewVersion = Long.MIN_VALUE;
    /** Whether the in-world block-diff preview is toggled on via the "Show Changes" button. */
    private static boolean showChangesEnabled = false;
    /** null when browsing the shared "All" view; otherwise the single claimed chunk whose own tab
     * is currently open (see ChronosphereScreen#selectedViewChunkKey), so the boundary renderer can
     * outline that one chunk even when it sits inside a larger claim and wouldn't otherwise get
     * walls on every side. */
    private static ChunkPos selectedViewChunk = null;

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
        gluedRegions = new ArrayList<>();
        previewVersion = Long.MIN_VALUE;
        showChangesEnabled = false;
        selectedViewChunk = null;
    }

    /** Tells the boundary renderer which single claimed chunk's own tab (if any) is currently
     * open, so it can outline that chunk specifically. Pass null for the shared "All" view. */
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
    public static long getPreviewVersion() { return previewVersion; }
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
     * chunk in previewChunkSnapshots (not just whichever chunk the graph is displaying) — calls the
     * same {@link TemporalTimeline#walkDeltas} the server's real jump
     * ({@link io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline#applyChunkAtTime})
     * uses, so the preview can't hand-drift out of sync with what Jump will actually do. Known gap:
     * a jump that crosses a re-snapshot boundary (see walkDeltas's doc) needs a full chunk baseline
     * the client never receives, so the preview under-reports changes in that case specifically. */
    public static List<ProjectionEntry> getProjectionEntries(Level level) {
        if (!hasActivePreview()) return List.of();

        List<ProjectionEntry> entries = new ArrayList<>();
        for (ChunkTimelineSnapshot snapshot : previewChunkSnapshots) {
            appendProjectionEntries(level, snapshot, entries);
        }
        return entries;
    }

    /** Prefers the exact selected commit id when this chunk's own commit list contains it,
     * falling back to gameTime-based resolution only for chunks that don't (e.g. a Chronosphere
     * preview chunk the selected mark never touched). Mirrors why setSelectedCommit() stores an
     * exact id in the first place: resolveNearest alone is ambiguous once a BRANCH marker shares
     * the exact gameTime of the commit it forked from, which routinely happens right after jumping
     * to one of two same-lineage marks and then selecting the other. */
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

    /** Mirrors TemporalWorldData#isGlued server-side: true when pos sits inside any glued region
     * synced for the active machine's dimension, meaning a jump would leave it untouched. */
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
