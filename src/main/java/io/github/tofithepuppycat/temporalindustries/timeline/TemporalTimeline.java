package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
 * Per-dimension tree of TemporalCommits, linked by parentId.
 *
 * <ul>
 *   <li>DELTA: what changed in one flush interval, bundling every chunk touched. Forms the
 *       dimension-wide trunk ({@code headId}); ordinary gameplay never forks it.
 *   <li>SNAPSHOT: on-demand full baseline of all tracked chunks; also attaches to the trunk.
 *   <li>BRANCH: zero-diff fork marker for checking out a point in time on ONE chunk (see
 *       {@link TemporalCommit#getBranchChunkPos()}); never affects any other chunk's timeline.
 * </ul>
 *
 * Each chunk's own lineage is tracked separately from creation order via {@code chunkLocalParent}
 * (chunk-scoped parent per commit) and {@code chunkHeadId} (what that chunk's live world currently
 * reflects) — like {@code git checkout}: landing on a childless point just moves the head there
 * for free, while checking out a point with existing history forks a new branch (see branch()).
 *
 * Rollback for a chunk, from its current head to target T: walk chunkLocalParent links from both
 * to find their common-prefix fork point, undo the head chain past it, then replay T's chain past it.
 */
public class TemporalTimeline {
    private long nextId = 1;
    /** Head of the dimension-wide trunk (DELTA/SNAPSHOT only); bookkeeping only, rollback
     * correctness is resolved per chunk. BRANCH commits never move this. */
    private long headId = -1L;
    private final LinkedList<TemporalCommit> commits = new LinkedList<>();
    private final Map<Long, TemporalCommit> byId = new HashMap<>();
    /** chunkPos.toLong() -> commit IDs relevant to that chunk, in creation order. */
    private final Map<Long, List<Long>> chunkIndex = new HashMap<>();
    /** chunkPos.toLong() -> (commitId -> the id it locally forked from within that chunk's
     * history, or -1 for a root). Recorded explicitly at commit time. */
    private final Map<Long, Map<Long, Long>> chunkLocalParent = new HashMap<>();
    /** chunkPos.toLong() -> the commit that chunk's live world currently reflects. */
    private final Map<Long, Long> chunkHeadId = new HashMap<>();

    // -------------------------------------------------------------------------
    // Mutation

    /** Forgets chunkPos's entire recorded history — every commit that touched it, its local-parent
     * links, and its head — without touching the live world. Commits that also touch OTHER chunks
     * (a shared DELTA, say) are left registered globally; only this chunk's own reference to them is
     * dropped, same as if it had never been tracked. Callers that want history to resume afterward
     * still need to give the chunk a fresh baseline (see {@code ensureBaseline}), since without one
     * it has no anchor to walk from. */
    public void clearChunkHistory(ChunkPos chunkPos) {
        long chunkKey = chunkPos.toLong();
        chunkIndex.remove(chunkKey);
        chunkLocalParent.remove(chunkKey);
        chunkHeadId.remove(chunkKey);
    }

    public TemporalCommit addDelta(long gameTime, List<ChunkDelta> chunkDeltas) {
        TemporalCommit commit = TemporalCommit.delta(nextId++, headId, gameTime, chunkDeltas);
        registerCommit(commit);
        return commit;
    }

    public TemporalCommit addSnapshot(long gameTime, List<ChunkSnapshot> chunkSnapshots) {
        TemporalCommit commit = TemporalCommit.snapshot(nextId++, headId, gameTime, chunkSnapshots);
        registerCommit(commit);
        return commit;
    }

    /** How many commits chunkPos's head is past its nearest snapshot ancestor (inclusive of the
     * snapshot itself) needs to reach before a fresh {@link ChunkSnapshot} baseline is due — shared
     * by the periodic auto-tracking re-snapshot check ({@code AbstractTimelineMachineBlockEntity})
     * and a Portable ChronoMarker's manual save point (see {@code PortableChronoMarkerItem}), so a
     * player-triggered save re-baselines under exactly the same rule an automatic one would. */
    public static final int SNAPSHOT_COMMIT_THRESHOLD = 50;

    /** Ensures chunkPos has at least one commit to anchor its history walk — called centrally from
     * {@link io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData#trackChunk} so every
     * owner (Time Machine, Chronosphere, Portable ChronoMarker) gets this guarantee for free instead
     * of each having to remember to pair trackChunk with a baseline call itself.
     * @return true if a baseline snapshot was actually created (chunk had no commits yet) */
    public boolean ensureBaseline(ChunkPos chunkPos, ServerLevel level) {
        if (!getCommitsForChunk(chunkPos).isEmpty()) return false;
        addSnapshot(level.getGameTime(), List.of(ChunkSnapshot.capture(level, chunkPos)));
        return true;
    }

    /** Checks out targetGameTime for chunkPos, preferring preferredCommitId as the exact checkout
     * target over resolveNearest's gameTime-only match when it's actually one of chunkPos's own
     * commits (pass {@link TemporalCommit#NO_PREFERRED_COMMIT} to always resolve by gameTime alone).
     * A branch marker always shares its exact gameTime with the commit it forked from (see
     * TemporalCommit#resolveNearest's tie-break), so re-deriving the target from gameTime alone
     * can't distinguish between two different branches' commits at the same tick — the caller
     * hands back the literal id of whichever node was actually selected to disambiguate.
     *
     * <p>No-op if this chunk has no history yet, or its head is already at the resolved point.
     * Landing on a childless point just moves the chunk's head there (no new commit); otherwise
     * forks a zero-diff marker commit scoped to this chunk. */
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

    /** targetGameTime resolved to a specific commit for chunkPos — preferredCommitId directly, if
     * it's actually one of chunkPos's own commits, letting an exact node selection bypass
     * resolveNearest's gameTime-only tie-break; otherwise the nearest match by gameTime, as before. */
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

    // -------------------------------------------------------------------------
    // Queries

    /** Every commit relevant to chunkPos — commits touching it plus its own branch markers — in creation order. */
    public List<TemporalCommit> getCommitsForChunk(ChunkPos chunkPos) {
        List<Long> ids = chunkIndex.getOrDefault(chunkPos.toLong(), Collections.emptyList());
        List<TemporalCommit> result = new ArrayList<>(ids.size());
        for (long id : ids) {
            TemporalCommit c = byId.get(id);
            if (c != null) result.add(c);
        }
        return result;
    }

    /** commitId → the id it locally forked from within chunkPos's own history (see class doc). */
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

    /**
     * The commit chunkPos's live world state currently reflects, or -1 if this chunk has no
     * history yet. Callers that are about to call branch() (which can move this) must capture it
     * first: see applyChunkAtTime's fromCommitId parameter.
     */
    public long getChunkHeadId(ChunkPos chunkPos) {
        long chunkKey = chunkPos.toLong();
        Long head = chunkHeadId.get(chunkKey);
        if (head != null) return head;
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        return chunkCommits.isEmpty() ? -1L : chunkCommits.get(chunkCommits.size() - 1).getId();
    }

    public boolean isEmpty() { return commits.isEmpty(); }

    /** How many commits chunkPos's head is past its nearest snapshot ancestor (inclusive of the
     * snapshot itself) — reuses ancestryChain's own bounded walk, so this stays cheap regardless
     * of how deep the chunk's full history actually is. Used to decide when a fresh
     * {@link ChunkSnapshot} is due (see the block entities' periodic re-snapshot check). */
    public int getCommitsSinceSnapshot(ChunkPos chunkPos) {
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        if (chunkCommits.isEmpty()) return 0;
        long headId = getChunkHeadId(chunkPos);
        Map<Long, Long> localParents = getLocalParentsForChunk(chunkPos);
        return TemporalCommit.ancestryChain(chunkCommits, localParents, headId).size();
    }

    // -------------------------------------------------------------------------
    // World application

    /** The block/block-entity/entity state chunkPos would have at targetGameTime, transitioning
     * from fromCommitId — built by undoing the live lineage past its fork with the target lineage,
     * then replaying the target lineage past that same fork. Read-only: computing this touches
     * nothing in the world. */
    private record DesiredState(Map<BlockPos, BlockState> states, Map<BlockPos, CompoundTag> blockEntityTags,
                                 Map<UUID, CompoundTag> entityTags) {}

    /** Result of walking chunkPos's commit graph from fromCommitId to targetCommitId over ordinary
     * DELTA/BRANCH commits only — the common-prefix/undo/replay algorithm both the server's real
     * jump (via {@link #resolveDesiredState}) and the client's ghost-preview projection
     * ({@link io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager})
     * need, extracted here as a static, world-independent function so the two can never hand-drift
     * apart. commonPrefixLen and targetChain are exposed for the server's disjoint-lineage/SNAPSHOT
     * fallback below, which needs a {@link ChunkSnapshot}'s full baseline (server-only data, never
     * sent to the client — see that class's doc) that this walk alone can't produce. */
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

        // If the target lineage shares no history with the live one, its chain starts fresh from
        // its own nearest SNAPSHOT ancestor (see TemporalCommit#ancestryChain) — a full baseline
        // whose block grid must be applied directly, since a SNAPSHOT itself carries no ChunkDelta
        // for the undo/replay walk above to see. Without this, jumping onto a disjoint lineage (e.g.
        // back to the trunk after a re-snapshot happened while branched off it) applies nothing at
        // all for everything the snapshot baselined, leaving the world stuck on the live branch.
        // Server-only: a ChunkSnapshot's full block grid is never sent to the client (see its class
        // doc), so the client's identical walkDeltas() call can't and doesn't attempt this — its
        // ghost preview is a known-incomplete approximation across a re-snapshot boundary.
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

    /** Applies the world state for chunkPos at targetGameTime, transitioning from fromCommitId — the
     * commit this chunk's live world currently reflects (callers must capture this via
     * getChunkHeadId(chunkPos) before calling branch() for the same checkout) — resolving the target
     * the same preferredCommitId-aware way as {@link #branch(ChunkPos, long, long)} (pass the same
     * preferredCommitId to both calls for one checkout so they can never disagree on which commit
     * the target actually is), and never overwriting a position isGlued reports true for (see
     * item.TemporalGlueItem), leaving it exactly as the live world has it. */
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
     * <p>Positions are visited bottom-up ({@link ChunkSnapshot#BOTTOM_UP_ORDER}) in two passes
     * instead of one raw, arbitrarily-ordered pass: the first pass writes every changed block with
     * {@code UPDATE_KNOWN_SHAPE} set, which skips the recursive neighbor-shape/destroy cascade
     * ({@code Level#setBlock} normally runs synchronously on every single write) and skips neighbor
     * -changed notifications — so an about-to-be-restored support block (a wall under a torch, sand
     * under sand) can never desync or get destroyed by a transient, mid-restore configuration that
     * never actually existed. Only once every position in this chunk already holds its final state
     * does the second pass fire the normal neighbor-shape and neighbor-changed updates, so gravity
     * blocks, redstone, and attachment checks all evaluate against the real final result instead of
     * an arbitrary partial one. This mirrors how vanilla's structure-template placement avoids the
     * same cascade hazard. */
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
     * every block position whose state would actually change (positions the target state already
     * matches cost nothing). Read-only — same fromCommitId-capture rule as applyChunkAtTime.
     * Resolves the target the same preferredCommitId-aware way as {@link #branch(ChunkPos, long, long)},
     * and never charges for a glued position (see {@link #applyChunkAtTime(ChunkPos, long, long, ServerLevel, long, Predicate)}),
     * since a jump will never actually touch it. */
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

    // -------------------------------------------------------------------------
    // Serialization

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("NextId", nextId);
        ListTag commitList = new ListTag();
        for (TemporalCommit commit : commits) commitList.add(commit.toTag());
        tag.put("Commits", commitList);

        // Persisted explicitly rather than re-derived by replaying Commits, since a free checkout
        // can't be reconstructed from creation order alone.
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
