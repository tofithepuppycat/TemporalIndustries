package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Per-dimension tree of TemporalCommits, linked by parentId. DELTA commits bundle everything
 * changed in one flush interval and form the dimension-wide trunk; SNAPSHOT is an on-demand full
 * baseline also on the trunk; BRANCH is a zero-diff fork marker scoped to one chunk.
 *
 * <p>Each chunk's lineage is tracked separately via {@code chunkLocalParent} (chunk-scoped parent
 * per commit) and {@code chunkHeadId} (what the live world currently reflects), like
 * {@code git checkout}: landing on a childless point just moves the head; checking out a point
 * with existing history forks a new branch. Rollback walks both chains to their common-prefix
 * fork point, undoes the head chain past it, then replays the target chain past it.
 */
public class TemporalTimeline {
    private long nextId = 1;
    /** Head of the dimension-wide trunk (DELTA/SNAPSHOT only); bookkeeping only, since rollback
     * correctness is resolved per chunk. BRANCH commits never move this. */
    private long headId = -1L;
    private final LinkedList<TemporalCommit> commits = new LinkedList<>();
    private final Map<Long, TemporalCommit> byId = new HashMap<>();
    /** chunkPos.toLong() -> commit IDs relevant to that chunk, in creation order. */
    private final Map<Long, List<Long>> chunkIndex = new HashMap<>();
    /** chunkPos.toLong() -> (commitId -> the id it locally forked from, or -1 for a root). */
    private final Map<Long, Map<Long, Long>> chunkLocalParent = new HashMap<>();
    /** chunkPos.toLong() -> the commit that chunk's live world currently reflects. */
    private final Map<Long, Long> chunkHeadId = new HashMap<>();

    /** Forgets chunkPos's entire recorded history without touching the live world. Commits shared
     * with other chunks stay registered globally; only this chunk's reference is dropped. Callers
     * needing history to resume must give the chunk a fresh baseline afterward. */
    public void clearChunkHistory(ChunkPos chunkPos) {
        long chunkKey = chunkPos.toLong();
        chunkIndex.remove(chunkKey);
        chunkLocalParent.remove(chunkKey);
        chunkHeadId.remove(chunkKey);
    }

    public TemporalCommit addDelta(long gameTime, List<ChunkDelta> chunkDeltas) {
        return addDelta(gameTime, chunkDeltas, false);
    }

    /** @param playerMarked whether this came from a Portable ChronoMarker save rather than automatic tracking */
    public TemporalCommit addDelta(long gameTime, List<ChunkDelta> chunkDeltas, boolean playerMarked) {
        TemporalCommit commit = TemporalCommit.delta(nextId++, headId, gameTime, chunkDeltas, playerMarked);
        registerCommit(commit);
        return commit;
    }

    public TemporalCommit addSnapshot(long gameTime, List<ChunkSnapshot> chunkSnapshots) {
        return addSnapshot(gameTime, chunkSnapshots, false);
    }

    /** @param playerMarked whether this came from a Portable ChronoMarker save rather than automatic tracking */
    public TemporalCommit addSnapshot(long gameTime, List<ChunkSnapshot> chunkSnapshots, boolean playerMarked) {
        TemporalCommit commit = TemporalCommit.snapshot(nextId++, headId, gameTime, chunkSnapshots, playerMarked);
        registerCommit(commit);
        return commit;
    }

    /** Commits-since-snapshot threshold before a fresh {@link ChunkSnapshot} baseline is due;
     * shared by auto-tracking and manual Portable ChronoMarker saves. */
    public static final int SNAPSHOT_COMMIT_THRESHOLD = 50;

    /** Ensures chunkPos has at least one commit to anchor its history walk.
     * @return true if a baseline snapshot was actually created (chunk had no commits yet) */
    public boolean ensureBaseline(ChunkPos chunkPos, ServerLevel level) {
        if (!getCommitsForChunk(chunkPos).isEmpty()) return false;
        addSnapshot(level.getGameTime(), List.of(ChunkSnapshot.capture(level, chunkPos)));
        return true;
    }

    /** Checks out targetGameTime for chunkPos, preferring preferredCommitId as the exact target
     * over gameTime-only resolution when it's one of chunkPos's own commits (pass
     * {@link TemporalCommit#NO_PREFERRED_COMMIT} to always resolve by gameTime alone).
     *
     * <p>No-op if this chunk has no history yet, or its head is already at the resolved point.
     * Landing on a childless point just moves the chunk's head there; otherwise forks a zero-diff
     * marker commit scoped to this chunk. */
    @Nullable
    public TemporalCommit branch(ChunkPos chunkPos, long targetGameTime, long preferredCommitId) {
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        if (chunkCommits.isEmpty()) return null;

        long chunkKey = chunkPos.toLong();
        Map<Long, Long> localParents = chunkLocalParent.getOrDefault(chunkKey, Collections.emptyMap());
        long currentHead = chunkHeadId.getOrDefault(chunkKey, chunkCommits.get(chunkCommits.size() - 1).getId());
        long parent = resolveTarget(chunkCommits, targetGameTime, preferredCommitId);
        if (parent == currentHead) return null;

        if (!TemporalCommit.hasChild(localParents, parent)) {
            chunkHeadId.put(chunkKey, parent);
            return null;
        }

        TemporalCommit marker = TemporalCommit.branch(nextId++, parent, targetGameTime, chunkKey);
        registerCommit(marker);
        return marker;
    }

    /** targetGameTime resolved to a specific commit for chunkPos — preferredCommitId directly if
     * it's one of chunkPos's own commits, otherwise the nearest match by gameTime. */
    private long resolveTarget(List<TemporalCommit> chunkCommits, long targetGameTime, long preferredCommitId) {
        if (preferredCommitId != TemporalCommit.NO_PREFERRED_COMMIT) {
            for (TemporalCommit c : chunkCommits) {
                if (c.getId() == preferredCommitId) return preferredCommitId;
            }
        }
        return TemporalCommit.resolveNearest(chunkCommits, targetGameTime);
    }

    private void registerCommit(TemporalCommit commit) {
        commits.addLast(commit);
        byId.put(commit.getId(), commit);
        if (commit.getType() != TemporalCommit.Type.BRANCH) {
            headId = commit.getId();
        }
        for (ChunkDelta cd : commit.getChunkDeltas()) {
            long chunkKey = cd.getChunkPos().toLong();
            long localParent = chunkHeadId.getOrDefault(chunkKey, -1L);
            indexChunkTouch(chunkKey, commit.getId(), localParent);
        }
        for (ChunkSnapshot snapshot : commit.getChunkSnapshots()) {
            long chunkKey = snapshot.getChunkPos().toLong();
            long localParent = chunkHeadId.getOrDefault(chunkKey, -1L);
            indexChunkTouch(chunkKey, commit.getId(), localParent);
        }
        if (commit.getType() == TemporalCommit.Type.BRANCH) {
            indexChunkTouch(commit.getBranchChunkPos(), commit.getId(), commit.getParentId());
        }
    }

    private void indexChunkTouch(long chunkKey, long commitId, long localParentId) {
        chunkIndex.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(commitId);
        chunkLocalParent.computeIfAbsent(chunkKey, k -> new HashMap<>()).put(commitId, localParentId);
        chunkHeadId.put(chunkKey, commitId);
    }

    /** Every commit relevant to chunkPos, in creation order. */
    public List<TemporalCommit> getCommitsForChunk(ChunkPos chunkPos) {
        List<Long> ids = chunkIndex.getOrDefault(chunkPos.toLong(), Collections.emptyList());
        List<TemporalCommit> result = new ArrayList<>(ids.size());
        for (long id : ids) {
            TemporalCommit c = byId.get(id);
            if (c != null) result.add(c);
        }
        return result;
    }

    /** commitId → the id it locally forked from within chunkPos's own history. */
    public Map<Long, Long> getLocalParentsForChunk(ChunkPos chunkPos) {
        return Collections.unmodifiableMap(chunkLocalParent.getOrDefault(chunkPos.toLong(), Collections.emptyMap()));
    }

    public long getEarliestGameTimeForChunk(ChunkPos chunkPos) {
        List<Long> ids = chunkIndex.get(chunkPos.toLong());
        if (ids == null || ids.isEmpty()) return -1L;
        TemporalCommit first = byId.get(ids.get(0));
        return first == null ? -1L : first.getGameTime();
    }

    public long getLatestGameTimeForChunk(ChunkPos chunkPos) {
        List<Long> ids = chunkIndex.get(chunkPos.toLong());
        if (ids == null || ids.isEmpty()) return -1L;
        TemporalCommit last = byId.get(ids.get(ids.size() - 1));
        return last == null ? -1L : last.getGameTime();
    }

    @Nullable
    public TemporalCommit getCommitById(long id) {
        return byId.get(id);
    }

    public long getLatestCommitId() {
        return commits.isEmpty() ? -1L : commits.getLast().getId();
    }

    /** The commit chunkPos's live world state currently reflects, or -1 if no history yet.
     * Callers about to call branch() (which can move this) must capture it first. */
    public long getChunkHeadId(ChunkPos chunkPos) {
        long chunkKey = chunkPos.toLong();
        Long head = chunkHeadId.get(chunkKey);
        if (head != null) return head;
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        return chunkCommits.isEmpty() ? -1L : chunkCommits.get(chunkCommits.size() - 1).getId();
    }

    public boolean isEmpty() { return commits.isEmpty(); }

    /** How many commits chunkPos's head is past its nearest snapshot ancestor; used to decide
     * when a fresh {@link ChunkSnapshot} is due. */
    public int getCommitsSinceSnapshot(ChunkPos chunkPos) {
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        if (chunkCommits.isEmpty()) return 0;
        long headId = getChunkHeadId(chunkPos);
        Map<Long, Long> localParents = getLocalParentsForChunk(chunkPos);
        return TemporalCommit.ancestryChain(chunkCommits, localParents, headId).size();
    }

    // Manual save-point diffing (Portable ChronoMarker)

    /** chunkPos's nearest SNAPSHOT ancestor (inclusive) and every DELTA between it and the head, in
     * chain order. Returns an empty chain if chunkPos has no history, or its earliest reachable
     * commit isn't a SNAPSHOT (shouldn't occur once {@link #ensureBaseline} has run). */
    private List<TemporalCommit> headChainSinceSnapshot(ChunkPos chunkPos) {
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        if (chunkCommits.isEmpty()) return List.of();

        long headId = getChunkHeadId(chunkPos);
        Map<Long, Long> localParents = getLocalParentsForChunk(chunkPos);
        List<TemporalCommit> chain = TemporalCommit.ancestryChain(chunkCommits, localParents, headId);
        if (chain.isEmpty() || chain.get(0).getType() != TemporalCommit.Type.SNAPSHOT) return List.of();
        return chain;
    }

    /**
     * A {@link TemporalCommit.Type#DELTA}-ready diff of current (a just-captured full baseline)
     * against chunkPos's materialized head, letting a Portable ChronoMarker save produce ordinary
     * per-block deltas from two captures without continuous background tracking.
     *
     * <p>Compares section by section ({@link ChunkSnapshot#sectionTriviallyEquals}) rather than
     * expanding both snapshots into full position maps up front, so untouched sections (the common
     * case below the surface or above the build limit) never get expanded at all.
     * @return null if nothing actually changed since the head */
    @Nullable
    public ChunkDelta diffChunkAgainstHead(ResourceLocation dimension, ChunkSnapshot current) {
        ChunkPos chunkPos = current.getChunkPos();
        List<TemporalCommit> chain = headChainSinceSnapshot(chunkPos);
        if (chain.isEmpty()) return null;

        ChunkSnapshot baseline = null;
        for (ChunkSnapshot snapshot : chain.get(0).getChunkSnapshots()) {
            if (snapshot.getChunkPos().equals(chunkPos)) { baseline = snapshot; break; }
        }
        if (baseline == null) return null;

        // Sparse overlay of every DELTA recorded on top of the baseline (latest write wins),
        // proportional to positions actually changed rather than chunk size.
        Map<BlockPos, BlockState> overlayStates = new HashMap<>();
        Map<BlockPos, CompoundTag> overlayBeTags = new HashMap<>();
        Set<Integer> touchedSections = new HashSet<>();
        for (int i = 1; i < chain.size(); i++) {
            for (ChunkDelta cd : chain.get(i).getChunkDeltas()) {
                if (!cd.getChunkPos().equals(chunkPos)) continue;
                for (BlockChangeDelta change : cd.getBlockChanges()) {
                    overlayStates.put(change.getPos(), change.getNewState());
                    overlayBeTags.put(change.getPos(), change.getNewBlockEntityTag());
                    touchedSections.add(Math.floorDiv(change.getPos().getY(), 16));
                }
            }
        }

        Map<BlockPos, CompoundTag> currentBeTags = current.getBlockEntityTags();
        Map<BlockPos, CompoundTag> baselineBeTags = baseline.getBlockEntityTags();
        List<BlockChangeDelta> changes = new ArrayList<>();
        int sectionCount = Math.min(current.sectionCount(), baseline.sectionCount());
        for (int i = 0; i < sectionCount; i++) {
            int sectionY = current.minSectionY() + i;
            if (!touchedSections.contains(sectionY) && current.sectionTriviallyEquals(baseline, i)) {
                continue; // whole section matches the baseline, and nothing overlays it either
            }

            Map<BlockPos, BlockState> currentSection = new HashMap<>();
            current.collectSectionInto(i, currentSection);
            Map<BlockPos, BlockState> baselineSection = new HashMap<>();
            baseline.collectSectionInto(i, baselineSection);

            for (Map.Entry<BlockPos, BlockState> entry : currentSection.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState newState = entry.getValue();
                BlockState oldState = overlayStates.containsKey(pos) ? overlayStates.get(pos)
                        : baselineSection.getOrDefault(pos, newState);
                CompoundTag oldBeTag = overlayBeTags.containsKey(pos) ? overlayBeTags.get(pos) : baselineBeTags.get(pos);
                CompoundTag newBeTag = currentBeTags.get(pos);
                if (newState.equals(oldState) && Objects.equals(oldBeTag, newBeTag)) continue;
                changes.add(new BlockChangeDelta(pos, oldState, newState, oldBeTag, newBeTag));
            }
        }

        return changes.isEmpty() ? null : new ChunkDelta(dimension, chunkPos, changes);
    }

    /** The block/block-entity/entity state chunkPos would have at targetGameTime, transitioning
     * from fromCommitId — built by undoing the live lineage past its fork with the target lineage,
     * then replaying the target lineage past that fork. Read-only; touches nothing in the world. */
    private record DesiredState(Map<BlockPos, BlockState> states, Map<BlockPos, CompoundTag> blockEntityTags,
                                 Map<UUID, CompoundTag> entityTags) {}

    /** Result of walking chunkPos's commit graph from fromCommitId to targetCommitId over ordinary
     * DELTA/BRANCH commits — a static, world-independent function shared by the server's real jump
     * and the client's ghost-preview projection so the two can't drift apart. commonPrefixLen and
     * targetChain are exposed for the server's disjoint-lineage/SNAPSHOT fallback below. */
    public record DeltaWalkResult(Map<BlockPos, BlockState> states, Map<BlockPos, CompoundTag> blockEntityTags,
                                   Map<UUID, CompoundTag> entityTags, int commonPrefixLen,
                                   List<TemporalCommit> targetChain) {}

    public static DeltaWalkResult walkDeltas(List<TemporalCommit> chunkCommits, Map<Long, Long> localParents,
                                              ChunkPos chunkPos, long fromCommitId, long targetCommitId) {
        List<TemporalCommit> liveChain = TemporalCommit.ancestryChain(chunkCommits, localParents, fromCommitId);
        List<TemporalCommit> targetChain = TemporalCommit.ancestryChain(chunkCommits, localParents, targetCommitId);

        int commonPrefixLen = 0;
        int maxCommon = Math.min(liveChain.size(), targetChain.size());
        while (commonPrefixLen < maxCommon
                && liveChain.get(commonPrefixLen).getId() == targetChain.get(commonPrefixLen).getId()) {
            commonPrefixLen++;
        }

        Map<BlockPos, BlockState> desiredStates = new HashMap<>();
        Map<BlockPos, CompoundTag> desiredBETags = new HashMap<>();
        // entityId -> the NBT to spawn/restore it from, or null (present as a key) meaning it
        // should not exist. Same earliest/latest-touch-wins semantics as block state above.
        Map<UUID, CompoundTag> desiredEntityTags = new HashMap<>();

        // Undo whatever happened past the fork on the live lineage (earliest touch wins:
        // that's the position's state right as it diverged from the shared history).
        for (int i = liveChain.size() - 1; i >= commonPrefixLen; i--) {
            for (ChunkDelta cd : liveChain.get(i).getChunkDeltas()) {
                if (!cd.getChunkPos().equals(chunkPos)) continue;
                for (BlockChangeDelta change : cd.getBlockChanges()) {
                    desiredStates.put(change.getPos(), change.getPreviousState());
                    desiredBETags.put(change.getPos(), change.getPreviousBlockEntityTag());
                }
                for (EntityDelta change : cd.getEntityChanges()) {
                    switch (change.getType()) {
                        case SPAWNED -> desiredEntityTags.put(change.getEntityId(), null);
                        case REMOVED, MODIFIED -> desiredEntityTags.put(change.getEntityId(), change.getStateBefore());
                    }
                }
            }
        }

        // Replay whatever happened past the fork on the target lineage (latest touch wins).
        for (int i = commonPrefixLen; i < targetChain.size(); i++) {
            for (ChunkDelta cd : targetChain.get(i).getChunkDeltas()) {
                if (!cd.getChunkPos().equals(chunkPos)) continue;
                for (BlockChangeDelta change : cd.getBlockChanges()) {
                    desiredStates.put(change.getPos(), change.getNewState());
                    desiredBETags.put(change.getPos(), change.getNewBlockEntityTag());
                }
                for (EntityDelta change : cd.getEntityChanges()) {
                    switch (change.getType()) {
                        case SPAWNED, MODIFIED -> desiredEntityTags.put(change.getEntityId(), change.getStateAfter());
                        case REMOVED -> desiredEntityTags.put(change.getEntityId(), null);
                    }
                }
            }
        }

        return new DeltaWalkResult(desiredStates, desiredBETags, desiredEntityTags, commonPrefixLen, targetChain);
    }

    private DesiredState resolveDesiredState(ChunkPos chunkPos, long targetGameTime, long fromCommitId, long preferredCommitId) {
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        if (chunkCommits.isEmpty()) return new DesiredState(Map.of(), Map.of(), Map.of());

        Map<Long, Long> localParents = getLocalParentsForChunk(chunkPos);
        long targetCommitId = resolveTarget(chunkCommits, targetGameTime, preferredCommitId);

        DeltaWalkResult walk = walkDeltas(chunkCommits, localParents, chunkPos, fromCommitId, targetCommitId);
        Map<BlockPos, BlockState> desiredStates = new HashMap<>(walk.states());
        Map<BlockPos, CompoundTag> desiredBETags = new HashMap<>(walk.blockEntityTags());

        // If the target lineage shares no history with the live one, its chain starts fresh from its
        // own nearest SNAPSHOT ancestor — a full baseline that must be applied directly since a
        // SNAPSHOT carries no ChunkDelta for the undo/replay walk above to see. Server-only: the
        // client's ghost preview can't do this and is a known-incomplete approximation here.
        List<TemporalCommit> targetChain = walk.targetChain();
        if (walk.commonPrefixLen() == 0 && !targetChain.isEmpty() && targetChain.get(0).getType() == TemporalCommit.Type.SNAPSHOT) {
            for (ChunkSnapshot snapshot : targetChain.get(0).getChunkSnapshots()) {
                if (!snapshot.getChunkPos().equals(chunkPos)) continue;
                desiredStates.putAll(snapshot.toBlockStateMap());
                desiredBETags.putAll(snapshot.getBlockEntityTags());
            }
        }

        return new DesiredState(desiredStates, desiredBETags, walk.entityTags());
    }

    /** Applies the world state for chunkPos at targetGameTime, transitioning from fromCommitId (the
     * commit the live world currently reflects — capture via getChunkHeadId before calling branch()
     * for the same checkout). Never overwrites a position isGlued reports true for. */
    public void applyChunkAtTime(ChunkPos chunkPos, long targetGameTime, long fromCommitId, ServerLevel level,
                                  long preferredCommitId, Predicate<BlockPos> isGlued) {
        DesiredState desired = resolveDesiredState(chunkPos, targetGameTime, fromCommitId, preferredCommitId);
        applyBlockStates(desired, level, isGlued);

        for (Map.Entry<UUID, CompoundTag> entry : desired.entityTags().entrySet()) {
            Entity existing = level.getEntity(entry.getKey());
            CompoundTag desiredTag = entry.getValue();
            if (existing != null) {
                existing.discard();
            }
            if (desiredTag != null) {
                Entity restored = EntityType.loadEntityRecursive(desiredTag, level, Function.identity());
                if (restored != null) {
                    level.addFreshEntity(restored);
                }
            }
        }
    }

    /** Writes desired's resolved block states (and block-entity tags) into the live world.
     *
     * <p>Two passes, bottom-up ({@link ChunkSnapshot#BOTTOM_UP_ORDER}): the first writes every
     * changed block with {@code UPDATE_KNOWN_SHAPE}, skipping neighbor-shape/destroy cascades so a
     * support block about to be restored can't desync against a transient mid-restore state. The
     * second pass then fires normal neighbor updates once the chunk's final state is fully in
     * place, mirroring how vanilla structure-template placement avoids the same hazard. */
    private void applyBlockStates(DesiredState desired, ServerLevel level, Predicate<BlockPos> isGlued) {
        List<BlockPos> positions = new ArrayList<>(desired.states().keySet());
        positions.removeIf(isGlued);
        positions.sort(ChunkSnapshot.BOTTOM_UP_ORDER);

        List<BlockPos> changed = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            BlockState desiredState = desired.states().get(pos);
            if (!level.getBlockState(pos).equals(desiredState)) {
                level.setBlock(pos, desiredState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                changed.add(pos);
            }

            CompoundTag beTag = desired.blockEntityTags().get(pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null && beTag != null) {
                CompoundTag restored = beTag.copy();
                restored.putInt("x", pos.getX());
                restored.putInt("y", pos.getY());
                restored.putInt("z", pos.getZ());
                be.loadWithComponents(restored, level.registryAccess());
                be.setChanged();
            }
        }

        // Settle pass: now that this chunk's final configuration is fully in place, run the shape
        // and neighbor-changed updates that were deliberately skipped above.
        for (BlockPos pos : changed) {
            BlockState state = level.getBlockState(pos);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
            level.blockUpdated(pos, state.getBlock());
        }
    }

    /** The energy cost of jumping chunkPos to targetGameTime from fromCommitId: costFn summed over
     * every block position whose state would actually change. Read-only; never charges for a
     * glued position, since a jump will never touch it. */
    public long computeJumpCost(ChunkPos chunkPos, long targetGameTime, long fromCommitId, Level level,
                                 ToLongFunction<BlockState> costFn, long preferredCommitId, Predicate<BlockPos> isGlued) {
        DesiredState desired = resolveDesiredState(chunkPos, targetGameTime, fromCommitId, preferredCommitId);
        long total = 0L;
        for (Map.Entry<BlockPos, BlockState> entry : desired.states().entrySet()) {
            if (isGlued.test(entry.getKey())) continue;
            BlockState desiredState = entry.getValue();
            if (!level.getBlockState(entry.getKey()).equals(desiredState)) {
                total += costFn.applyAsLong(desiredState);
            }
        }
        return total;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("NextId", nextId);
        ListTag commitList = new ListTag();
        for (TemporalCommit commit : commits) commitList.add(commit.toTag());
        tag.put("Commits", commitList);

        // Persisted explicitly, since a free checkout can't be reconstructed from creation order alone.
        ListTag headList = new ListTag();
        for (Map.Entry<Long, Long> entry : chunkHeadId.entrySet()) {
            CompoundTag headTag = new CompoundTag();
            headTag.putLong("Chunk", entry.getKey());
            headTag.putLong("Head", entry.getValue());
            headList.add(headTag);
        }
        tag.put("ChunkHeads", headList);

        ListTag localParentList = new ListTag();
        for (Map.Entry<Long, Map<Long, Long>> chunkEntry : chunkLocalParent.entrySet()) {
            for (Map.Entry<Long, Long> commitEntry : chunkEntry.getValue().entrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putLong("Chunk", chunkEntry.getKey());
                entryTag.putLong("Commit", commitEntry.getKey());
                entryTag.putLong("Parent", commitEntry.getValue());
                localParentList.add(entryTag);
            }
        }
        tag.put("ChunkLocalParents", localParentList);
        return tag;
    }

    public static TemporalTimeline fromTag(CompoundTag tag) {
        TemporalTimeline timeline = new TemporalTimeline();
        timeline.nextId = tag.getLong("NextId");
        ListTag commitList = tag.getList("Commits", Tag.TAG_COMPOUND);
        for (int i = 0; i < commitList.size(); i++) {
            timeline.registerCommit(TemporalCommit.fromTag(commitList.getCompound(i)));
        }

        ListTag headList = tag.getList("ChunkHeads", Tag.TAG_COMPOUND);
        for (int i = 0; i < headList.size(); i++) {
            CompoundTag headTag = headList.getCompound(i);
            timeline.chunkHeadId.put(headTag.getLong("Chunk"), headTag.getLong("Head"));
        }

        ListTag localParentList = tag.getList("ChunkLocalParents", Tag.TAG_COMPOUND);
        for (int i = 0; i < localParentList.size(); i++) {
            CompoundTag entryTag = localParentList.getCompound(i);
            timeline.chunkLocalParent
                    .computeIfAbsent(entryTag.getLong("Chunk"), k -> new HashMap<>())
                    .put(entryTag.getLong("Commit"), entryTag.getLong("Parent"));
        }
        return timeline;
    }
}
