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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * One machine's repository: a tree of {@link RepoCommit}s (single parent per commit, root at
 * {@link #rootCommitId}), with one {@link #headCommitId} tracking the commit currently rendered
 * in-world. Unlike the old per-dimension {@link TemporalTimeline}, a repository belongs to a
 * single machine and each chunk's full/delta decision is tracked independently (see {@link
 * ChunkState}) rather than sharing one repo-wide counter.
 *
 * <p>Commits are created only by explicit player action ({@link #commit}) — there is no periodic
 * auto-flush. Jumping ({@link #jumpTo}) moves the head and updates in-world chunks but never
 * forks by itself; forking only happens lazily, the moment a new commit is made while the head
 * isn't at a tip (see {@link #commit}). Chunks untouched by the commits between the old and new
 * head are left exactly as rendered.
 */
public final class TemporalRepository {
    /** Auto-generated branch names, styled like git but without needing the player to type
     * anything: Alpha, Beta, Gamma, ... Omega, then Alpha2, Beta2, and so on. */
    private static final String[] BRANCH_NAME_POOL = {
            "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta", "Iota", "Kappa",
            "Lambda", "Mu", "Nu", "Xi", "Omicron", "Pi", "Rho", "Sigma", "Tau", "Upsilon", "Phi", "Chi", "Psi", "Omega"
    };

    private final ResourceLocation dimension;
    private long nextId = 1;
    private long rootCommitId = RepoCommit.NO_PARENT;
    private long headCommitId = RepoCommit.NO_PARENT;
    private final Map<Long, RepoCommit> commits = new HashMap<>();
    /** commitId -> number of commits whose parentId is commitId; used to detect fork points. */
    private final Map<Long, Integer> childCount = new HashMap<>();
    /** chunkKey -> that chunk's own full/delta bookkeeping, independent of every other chunk. */
    private final Map<Long, ChunkState> chunkStates = new HashMap<>();
    /** chunkKey -> that chunk's state the instant it was first claimed, captured eagerly rather
     * than lazily at the chunk's first commit. Without this, a chunk edited before ever being
     * committed would have no recorded "before" at all — the lazy per-commit FULL capture only
     * ever sees the already-edited state — so jumping to any commit before that first commit
     * would have nothing to revert the chunk to. Read-only fallback for {@link #resolveChunkStateAt};
     * never touched once set. */
    private final Map<Long, ChunkSnapshot> claimBaselines = new HashMap<>();
    /** Branch name -> the commit it currently points to. */
    private final Map<String, Long> branchTips = new HashMap<>();
    @Nullable private String currentBranchName = null;
    private int nextBranchNameIndex = 0;

    public TemporalRepository(ResourceLocation dimension) {
        this.dimension = dimension;
    }

    public ResourceLocation getDimension() { return dimension; }
    public boolean isEmpty() { return rootCommitId == RepoCommit.NO_PARENT; }
    public long getHeadCommitId() { return headCommitId; }
    public long getRootCommitId() { return rootCommitId; }

    /** The branch name currently checked out, or null if this repo has never committed. */
    @Nullable
    public String getCurrentBranchName() { return currentBranchName; }

    @Nullable
    public RepoCommit getCommit(long id) { return commits.get(id); }

    /** Whether pos has ever received a FULL entry in this repository's history yet. */
    public boolean hasBaseline(ChunkPos pos) {
        ChunkState state = chunkStates.get(pos.toLong());
        return state != null && state.hasBaseline;
    }

    /** Records pos's current state as its claim baseline, if it doesn't already have one.
     * Idempotent, so it's safe to call on every claim (including re-claims on world load) without
     * worrying about clobbering a baseline with a later, already-edited state. */
    public void captureClaimBaseline(ServerLevel level, ChunkPos pos) {
        claimBaselines.computeIfAbsent(pos.toLong(), k -> ChunkSnapshot.capture(level, pos));
    }

    public Collection<RepoCommit> getAllCommits() { return commits.values(); }

    /** Every named branch's current tip commit id. */
    public Map<String, Long> getBranchTips() { return Collections.unmodifiableMap(branchTips); }

    private static final class ChunkState {
        boolean hasBaseline;
        int deltaCount;
    }

    // -------------------------------------------------------------------------
    // Committing

    /**
     * Bundles pendingBlockChanges/pendingEntityChanges (both keyed by chunkPos.toLong()) into one
     * new commit attached as a child of the current head. Every touched chunk's entry is always
     * FULL — a complete {@link ChunkSnapshot} of that chunk at commit time — so that jumping to
     * any commit can read a chunk's state directly off it instead of walking back to the nearest
     * baseline and replaying deltas forward. This trades larger per-commit storage for commits and
     * rollbacks that are both O(1) per chunk.
     *
     * <p>If the current head already has another child (i.e. the head isn't a tip — the player
     * jumped back and is now committing new changes), this forks: the existing line keeps its
     * branch name and this commit starts a fresh one named with the next unused Greek letter.
     *
     * @return the new commit, or null if both maps were empty (nothing to commit)
     */
    @Nullable
    public RepoCommit commit(ServerLevel level, Map<Long, Map<BlockPos, BlockChangeDelta>> pendingBlockChanges,
                              Map<Long, List<EntityDelta>> pendingEntityChanges, long gameTime, @Nullable UUID author) {
        Set<Long> chunkKeys = new HashSet<>();
        chunkKeys.addAll(pendingBlockChanges.keySet());
        chunkKeys.addAll(pendingEntityChanges.keySet());
        if (chunkKeys.isEmpty()) return null;

        Map<Long, ChunkEntry> entries = new HashMap<>();
        for (long chunkKey : chunkKeys) {
            ChunkState state = chunkStates.computeIfAbsent(chunkKey, k -> new ChunkState());
            ChunkPos pos = new ChunkPos(chunkKey);
            entries.put(chunkKey, ChunkEntry.full(ChunkSnapshot.capture(level, pos)));
            state.hasBaseline = true;
            state.deltaCount = 0;
        }

        long parentId = headCommitId;
        long id = nextId++;
        RepoCommit newCommit = new RepoCommit(id, parentId, gameTime, author, entries);
        commits.put(id, newCommit);

        if (parentId == RepoCommit.NO_PARENT) {
            rootCommitId = id;
            if (currentBranchName == null) {
                currentBranchName = generateBranchName();
            }
        } else if (childCount.getOrDefault(parentId, 0) > 0 || currentBranchName == null) {
            currentBranchName = generateBranchName();
        }
        childCount.merge(parentId, 1, Integer::sum);

        branchTips.put(currentBranchName, id);
        headCommitId = id;
        return newCommit;
    }

    /** Ensures this repository has at least a root commit, baselining every chunk in chunks that
     * doesn't already have one. No-op (returns null) if chunks is empty or every chunk already
     * has history. */
    @Nullable
    public RepoCommit ensureBaseline(ServerLevel level, Collection<ChunkPos> chunks, long gameTime, @Nullable UUID author) {
        Map<Long, Map<BlockPos, BlockChangeDelta>> pendingBlocks = new HashMap<>();
        for (ChunkPos pos : chunks) {
            long key = pos.toLong();
            ChunkState state = chunkStates.get(key);
            if (state == null || !state.hasBaseline) {
                pendingBlocks.put(key, Collections.emptyMap());
            }
        }
        return commit(level, pendingBlocks, Collections.emptyMap(), gameTime, author);
    }

    private String generateBranchName() {
        String base = BRANCH_NAME_POOL[nextBranchNameIndex % BRANCH_NAME_POOL.length];
        int cycle = nextBranchNameIndex / BRANCH_NAME_POOL.length;
        nextBranchNameIndex++;
        return cycle == 0 ? base : base + (cycle + 1);
    }

    // -------------------------------------------------------------------------
    // Navigation (ancestry, LCA, touched-chunk diffing)

    /** commitId's ancestry, root-first, commitId last. Empty if commitId isn't known. */
    private List<RepoCommit> ancestryChain(long commitId) {
        LinkedList<RepoCommit> chain = new LinkedList<>();
        long cur = commitId;
        while (cur != RepoCommit.NO_PARENT) {
            RepoCommit c = commits.get(cur);
            if (c == null) break;
            chain.addFirst(c);
            cur = c.getParentId();
        }
        return chain;
    }

    /** The lowest common ancestor of a and b, or {@link RepoCommit#NO_PARENT} if they share none
     * (shouldn't occur within one repository's single-rooted tree, barring an invalid id). */
    private long findLowestCommonAncestor(long a, long b) {
        List<RepoCommit> chainA = ancestryChain(a);
        List<RepoCommit> chainB = ancestryChain(b);
        int max = Math.min(chainA.size(), chainB.size());
        long lca = RepoCommit.NO_PARENT;
        for (int i = 0; i < max && chainA.get(i).getId() == chainB.get(i).getId(); i++) {
            lca = chainA.get(i).getId();
        }
        return lca;
    }

    /** Every chunk key touched by any commit strictly between fromExclusive and toInclusive,
     * walking toInclusive's parent chain. */
    private Set<Long> touchedChunksBetween(long fromExclusive, long toInclusive) {
        Set<Long> touched = new HashSet<>();
        long cur = toInclusive;
        while (cur != fromExclusive && cur != RepoCommit.NO_PARENT) {
            RepoCommit c = commits.get(cur);
            if (c == null) break;
            touched.addAll(c.getChunkEntries().keySet());
            cur = c.getParentId();
        }
        return touched;
    }

    private record ResolvedChunkState(Map<BlockPos, BlockState> states, Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntityTags,
                                       Map<UUID, net.minecraft.nbt.CompoundTag> entityTags) {
        static final ResolvedChunkState EMPTY = new ResolvedChunkState(Map.of(), Map.of(), Map.of());
    }

    /** chunkKey's resolved block/block-entity/entity state at commitId: walks commitId's ancestry
     * back to the nearest FULL entry for that chunk, or — if no commit ever gave it one, meaning
     * every commit touching it so far was made before it had a baseline — falls back to its
     * {@link #claimBaselines} snapshot from the moment it was claimed, then overlays every entry
     * after that point up to commitId (latest write wins, same as a full/delta commit's natural
     * git-like history). Read-only; touches nothing in the world. */
    private ResolvedChunkState resolveChunkStateAt(long commitId, long chunkKey) {
        List<RepoCommit> chain = ancestryChain(commitId);
        int fullIndex = -1;
        for (int i = chain.size() - 1; i >= 0; i--) {
            ChunkEntry e = chain.get(i).getChunkEntries().get(chunkKey);
            if (e != null && e.getType() == ChunkEntry.Type.FULL) { fullIndex = i; break; }
        }

        ChunkSnapshot baseline;
        int startIndex;
        if (fullIndex >= 0) {
            baseline = chain.get(fullIndex).getChunkEntries().get(chunkKey).getSnapshot();
            startIndex = fullIndex + 1;
        } else {
            baseline = claimBaselines.get(chunkKey);
            if (baseline == null) return ResolvedChunkState.EMPTY;
            startIndex = 0;
        }
        Map<BlockPos, BlockState> states = new HashMap<>(baseline.toBlockStateMap());
        Map<BlockPos, net.minecraft.nbt.CompoundTag> beTags = new HashMap<>(baseline.getBlockEntityTags());
        Map<UUID, net.minecraft.nbt.CompoundTag> entityTags = new HashMap<>();

        for (int i = startIndex; i < chain.size(); i++) {
            ChunkEntry e = chain.get(i).getChunkEntries().get(chunkKey);
            if (e == null) continue;
            if (e.getType() == ChunkEntry.Type.FULL) {
                states.clear();
                beTags.clear();
                states.putAll(e.getSnapshot().toBlockStateMap());
                beTags.putAll(e.getSnapshot().getBlockEntityTags());
            } else {
                for (BlockChangeDelta change : e.getBlockChanges()) {
                    states.put(change.getPos(), change.getNewState());
                    beTags.put(change.getPos(), change.getNewBlockEntityTag());
                }
                for (EntityDelta change : e.getEntityChanges()) {
                    switch (change.getType()) {
                        case SPAWNED, MODIFIED -> entityTags.put(change.getEntityId(), change.getStateAfter());
                        case REMOVED -> entityTags.put(change.getEntityId(), null);
                    }
                }
            }
        }
        return new ResolvedChunkState(states, beTags, entityTags);
    }

    /** Every chunk whose world state could change by moving the head from fromCommitId to
     * targetCommitId — i.e. touched by some commit on either side of their lowest common ancestor.
     * Chunks outside this set are deliberately left exactly as currently rendered. */
    private Set<Long> chunksAffectedByJump(long fromCommitId, long targetCommitId) {
        long lca = findLowestCommonAncestor(fromCommitId, targetCommitId);
        Set<Long> touched = new HashSet<>(touchedChunksBetween(lca, fromCommitId));
        touched.addAll(touchedChunksBetween(lca, targetCommitId));
        return touched;
    }

    // -------------------------------------------------------------------------
    // Read-only preview

    /**
     * Every chunk affected by jumping from the current head to targetCommitId, mapped to that
     * chunk's fully resolved target block states. Shares {@link #chunksAffectedByJump} and
     * {@link #resolveChunkStateAt} with the real {@link #jumpTo}, so the client's "Show Changes"
     * ghost preview can never diverge from what a jump would actually write. Touches nothing in
     * the world and creates no commits.
     */
    public Map<ChunkPos, Map<BlockPos, BlockState>> previewJumpTargetStates(long targetCommitId) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return Map.of();

        Map<ChunkPos, Map<BlockPos, BlockState>> result = new HashMap<>();
        for (long chunkKey : chunksAffectedByJump(headCommitId, targetCommitId)) {
            result.put(new ChunkPos(chunkKey), resolveChunkStateAt(targetCommitId, chunkKey).states());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Branch deletion / full reset

    /**
     * Deletes branchName and the history unique to it. Refuses (returns false) when branchName
     * doesn't exist, or when its tip is on the current head's ancestry path — i.e. that line is
     * what the world currently reflects, so dropping it would strand the head.
     *
     * <p>Only commits reachable *backward* from the branch tip that no other branch tip and not
     * the head also reach are removed; shared history and the root always survive. Per-chunk
     * full/delta bookkeeping is rebuilt afterwards from whatever history is left, so a chunk whose
     * only baseline lived on the deleted branch correctly gets a fresh FULL on its next commit.
     */
    public boolean deleteBranch(String branchName) {
        Long tipBoxed = branchTips.get(branchName);
        if (tipBoxed == null) return false;
        long tip = tipBoxed;

        // Walking up from the head rather than down from the tip: the tip being an ancestor of the
        // head (or the head itself) is exactly what "currently checked out" means here.
        for (RepoCommit c : ancestryChain(headCommitId)) {
            if (c.getId() == tip) return false;
        }

        Set<Long> keep = new HashSet<>();
        for (RepoCommit c : ancestryChain(headCommitId)) keep.add(c.getId());
        for (Map.Entry<String, Long> entry : branchTips.entrySet()) {
            if (entry.getKey().equals(branchName)) continue;
            for (RepoCommit c : ancestryChain(entry.getValue())) keep.add(c.getId());
        }

        long cur = tip;
        while (cur != RepoCommit.NO_PARENT && cur != rootCommitId && !keep.contains(cur)) {
            RepoCommit removed = commits.remove(cur);
            if (removed == null) break;
            long parentId = removed.getParentId();
            Integer count = childCount.get(parentId);
            if (count != null) {
                if (count <= 1) childCount.remove(parentId);
                else childCount.put(parentId, count - 1);
            }
            childCount.remove(cur);
            cur = parentId;
        }

        branchTips.remove(branchName);
        rebuildChunkStates();
        return true;
    }

    /**
     * Wipes this repository back to empty and immediately re-baselines every chunk in
     * chunksToRebaseline from live world state as a fresh root commit — the live world is never
     * touched, only the recorded history. Equivalent to the old model's deleteAllHistory.
     */
    public void reset(ServerLevel level, long gameTime, @Nullable UUID author, Collection<ChunkPos> chunksToRebaseline) {
        commits.clear();
        childCount.clear();
        chunkStates.clear();
        branchTips.clear();
        currentBranchName = null;
        nextBranchNameIndex = 0;
        nextId = 1;
        rootCommitId = RepoCommit.NO_PARENT;
        headCommitId = RepoCommit.NO_PARENT;

        // Every chunk is listed with no pending changes: chunkStates is empty, so commit() sees
        // each as un-baselined and captures a FULL entry for it.
        Map<Long, Map<BlockPos, BlockChangeDelta>> pendingBlocks = new HashMap<>();
        for (ChunkPos pos : chunksToRebaseline) pendingBlocks.put(pos.toLong(), Collections.emptyMap());
        commit(level, pendingBlocks, Collections.emptyMap(), gameTime, author);
    }

    /** Recomputes every chunk's hasBaseline/deltaCount from the current head's ancestry, after
     * commits have been removed. Scoped to the head's line because that's the only history a
     * subsequent commit's resolve would walk. */
    private void rebuildChunkStates() {
        chunkStates.clear();
        for (RepoCommit c : ancestryChain(headCommitId)) {
            for (Map.Entry<Long, ChunkEntry> entry : c.getChunkEntries().entrySet()) {
                ChunkState state = chunkStates.computeIfAbsent(entry.getKey(), k -> new ChunkState());
                if (entry.getValue().getType() == ChunkEntry.Type.FULL) {
                    state.hasBaseline = true;
                    state.deltaCount = 0;
                } else {
                    state.deltaCount++;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Jumping

    /** Moves the head to targetCommitId and updates every in-world chunk affected by the move
     * (see {@link #chunksAffectedByJump}); chunks the move doesn't touch are left as rendered.
     * Never creates a commit or a fork by itself — see {@link #commit} for lazy forking. Never
     * overwrites a position isGlued reports true for.
     * @return every position whose block state actually changed, so callers can animate the jump */
    public List<BlockPos> jumpTo(long targetCommitId, ServerLevel level, Predicate<BlockPos> isGlued) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return List.of();

        Set<Long> affectedChunks = chunksAffectedByJump(headCommitId, targetCommitId);
        List<BlockPos> deferredSettle = new ArrayList<>();
        Map<UUID, net.minecraft.nbt.CompoundTag> desiredEntities = new HashMap<>();

        for (long chunkKey : affectedChunks) {
            ResolvedChunkState desired = resolveChunkStateAt(targetCommitId, chunkKey);
            applyBlockStates(desired.states(), desired.blockEntityTags(), level, isGlued, deferredSettle);
            desiredEntities.putAll(desired.entityTags());
        }

        settleBlocks(level, deferredSettle);
        applyEntityStates(desiredEntities, level);

        headCommitId = targetCommitId;
        currentBranchName = null;
        for (Map.Entry<String, Long> entry : branchTips.entrySet()) {
            if (entry.getValue() == targetCommitId) { currentBranchName = entry.getKey(); break; }
        }
        return deferredSettle;
    }

    /** The energy cost of jumping to targetCommitId: costFn summed over every block position
     * (across every affected chunk) whose state would actually change, plus nodeJumpSurcharge if
     * targetCommitId differs from the current head at all. Read-only; never charges for a glued
     * position, since a jump will never touch it. */
    public long computeJumpCost(long targetCommitId, ServerLevel level, ToLongFunction<BlockState> costFn,
                                 Predicate<BlockPos> isGlued, long nodeJumpSurcharge) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return 0L;

        long total = nodeJumpSurcharge;
        for (long chunkKey : chunksAffectedByJump(headCommitId, targetCommitId)) {
            ResolvedChunkState desired = resolveChunkStateAt(targetCommitId, chunkKey);
            for (Map.Entry<BlockPos, BlockState> entry : desired.states().entrySet()) {
                if (isGlued.test(entry.getKey())) continue;
                if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                    total += costFn.applyAsLong(entry.getValue());
                }
            }
        }
        return total;
    }

    /** Two passes, bottom-up ({@link ChunkSnapshot#BOTTOM_UP_ORDER}): the first writes every
     * changed block with {@code UPDATE_KNOWN_SHAPE}, skipping neighbor-shape/destroy cascades so a
     * support block about to be restored can't desync against a transient mid-restore state. The
     * second ({@link #settleBlocks}) then fires normal neighbor updates once every chunk in this
     * jump has its final state in place. */
    private void applyBlockStates(Map<BlockPos, BlockState> states, Map<BlockPos, net.minecraft.nbt.CompoundTag> beTags,
                                   ServerLevel level, Predicate<BlockPos> isGlued, List<BlockPos> deferredSettle) {
        List<BlockPos> positions = new ArrayList<>(states.keySet());
        positions.removeIf(isGlued);
        positions.sort(ChunkSnapshot.BOTTOM_UP_ORDER);

        for (BlockPos pos : positions) {
            BlockState desiredState = states.get(pos);
            if (!level.getBlockState(pos).equals(desiredState)) {
                level.setBlock(pos, desiredState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                deferredSettle.add(pos);
            }

            net.minecraft.nbt.CompoundTag beTag = beTags.get(pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null && beTag != null) {
                net.minecraft.nbt.CompoundTag restored = beTag.copy();
                restored.putInt("x", pos.getX());
                restored.putInt("y", pos.getY());
                restored.putInt("z", pos.getZ());
                be.loadWithComponents(restored, level.registryAccess());
                be.setChanged();
            }
        }
    }

    private static void settleBlocks(ServerLevel level, List<BlockPos> changed) {
        for (BlockPos pos : changed) {
            BlockState state = level.getBlockState(pos);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
            level.blockUpdated(pos, state.getBlock());
        }
    }

    private static void applyEntityStates(Map<UUID, net.minecraft.nbt.CompoundTag> desiredEntities, ServerLevel level) {
        for (Map.Entry<UUID, net.minecraft.nbt.CompoundTag> entry : desiredEntities.entrySet()) {
            Entity existing = level.getEntity(entry.getKey());
            net.minecraft.nbt.CompoundTag desiredTag = entry.getValue();
            if (existing != null) existing.discard();
            if (desiredTag != null) {
                Entity restored = EntityType.loadEntityRecursive(desiredTag, level, Function.identity());
                if (restored != null) level.addFreshEntity(restored);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Persistence

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.toString());
        tag.putLong("NextId", nextId);
        tag.putLong("RootCommitId", rootCommitId);
        tag.putLong("HeadCommitId", headCommitId);
        if (currentBranchName != null) tag.putString("CurrentBranch", currentBranchName);
        tag.putInt("NextBranchNameIndex", nextBranchNameIndex);

        ListTag commitList = new ListTag();
        for (RepoCommit commit : commits.values()) commitList.add(commit.toTag());
        tag.put("Commits", commitList);

        ListTag childCountList = new ListTag();
        for (Map.Entry<Long, Integer> entry : childCount.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Commit", entry.getKey());
            entryTag.putInt("Count", entry.getValue());
            childCountList.add(entryTag);
        }
        tag.put("ChildCounts", childCountList);

        ListTag chunkStateList = new ListTag();
        for (Map.Entry<Long, ChunkState> entry : chunkStates.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Chunk", entry.getKey());
            entryTag.putBoolean("HasBaseline", entry.getValue().hasBaseline);
            entryTag.putInt("DeltaCount", entry.getValue().deltaCount);
            chunkStateList.add(entryTag);
        }
        tag.put("ChunkStates", chunkStateList);

        ListTag claimBaselineList = new ListTag();
        for (Map.Entry<Long, ChunkSnapshot> entry : claimBaselines.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Chunk", entry.getKey());
            entryTag.put("Snapshot", entry.getValue().toTag());
            claimBaselineList.add(entryTag);
        }
        tag.put("ClaimBaselines", claimBaselineList);

        ListTag branchList = new ListTag();
        for (Map.Entry<String, Long> entry : branchTips.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Name", entry.getKey());
            entryTag.putLong("Tip", entry.getValue());
            branchList.add(entryTag);
        }
        tag.put("Branches", branchList);

        return tag;
    }

    public static TemporalRepository fromTag(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        TemporalRepository repo = new TemporalRepository(dimension);
        repo.nextId = tag.getLong("NextId");
        repo.rootCommitId = tag.getLong("RootCommitId");
        repo.headCommitId = tag.getLong("HeadCommitId");
        repo.currentBranchName = tag.contains("CurrentBranch") ? tag.getString("CurrentBranch") : null;
        repo.nextBranchNameIndex = tag.getInt("NextBranchNameIndex");

        ListTag commitList = tag.getList("Commits", Tag.TAG_COMPOUND);
        for (int i = 0; i < commitList.size(); i++) {
            RepoCommit commit = RepoCommit.fromTag(commitList.getCompound(i));
            repo.commits.put(commit.getId(), commit);
        }

        ListTag childCountList = tag.getList("ChildCounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < childCountList.size(); i++) {
            CompoundTag entryTag = childCountList.getCompound(i);
            repo.childCount.put(entryTag.getLong("Commit"), entryTag.getInt("Count"));
        }

        ListTag chunkStateList = tag.getList("ChunkStates", Tag.TAG_COMPOUND);
        for (int i = 0; i < chunkStateList.size(); i++) {
            CompoundTag entryTag = chunkStateList.getCompound(i);
            ChunkState state = new ChunkState();
            state.hasBaseline = entryTag.getBoolean("HasBaseline");
            state.deltaCount = entryTag.getInt("DeltaCount");
            repo.chunkStates.put(entryTag.getLong("Chunk"), state);
        }

        ListTag claimBaselineList = tag.getList("ClaimBaselines", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimBaselineList.size(); i++) {
            CompoundTag entryTag = claimBaselineList.getCompound(i);
            repo.claimBaselines.put(entryTag.getLong("Chunk"), ChunkSnapshot.fromTag(entryTag.getCompound("Snapshot")));
        }

        ListTag branchList = tag.getList("Branches", Tag.TAG_COMPOUND);
        for (int i = 0; i < branchList.size(); i++) {
            CompoundTag entryTag = branchList.getCompound(i);
            repo.branchTips.put(entryTag.getString("Name"), entryTag.getLong("Tip"));
        }

        return repo;
    }
}
