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

/** Per-dimension tree of TemporalCommits, linked by parentId. */
public class TemporalTimeline {
    private long nextId = 1;
    /**
     * Head of the dimension-wide trunk (DELTA/SNAPSHOT only); bookkeeping only, since rollback
     * correctness is resolved per chunk.
     */
    private long headId = -1L;
    private final LinkedList<TemporalCommit> commits = new LinkedList<>();
    private final Map<Long, TemporalCommit> byId = new HashMap<>();
    /** chunkPos.toLong() -> commit IDs relevant to that chunk, in creation order. */
    private final Map<Long, List<Long>> chunkIndex = new HashMap<>();
    /** chunkPos.toLong() -> (commitId -> the id it locally forked from, or -1 for a root). */
    private final Map<Long, Map<Long, Long>> chunkLocalParent = new HashMap<>();
    /** chunkPos.toLong() -> the commit that chunk's live world currently reflects. */
    private final Map<Long, Long> chunkHeadId = new HashMap<>();

    /** Named refs into the commit graph (git-like branches), keyed by name. */
    private final Map<String, TemporalBranch> branches = new HashMap<>();
    public static final String MAIN_BRANCH = "main";
    /**
     * The branch currently checked out onto the live world; advances automatically as new
     * DELTA/SNAPSHOT commits land (see {@link #registerCommit}), same as a real git branch.
     */
    private String currentBranchName = MAIN_BRANCH;

    /** Forgets chunkPos's entire recorded history without touching the live world. */
    public void clearChunkHistory(ChunkPos chunkPos) {
        long chunkKey = chunkPos.toLong();
        chunkIndex.remove(chunkKey);
        chunkLocalParent.remove(chunkKey);
        chunkHeadId.remove(chunkKey);
    }

    /**
     * Deletes chunkPos's branchCommitId and everything forked from it within chunkPos's own local
     * history, provided the chunk's current head doesn't sit inside that subtree — i.e. the branch
     * being deleted isn't the one currently checked out.
     */
    public boolean deleteBranch(ChunkPos chunkPos, long branchCommitId) {
        TemporalCommit branch = byId.get(branchCommitId);
        if (branch == null || branch.getType() != TemporalCommit.Type.BRANCH) return false;

        long chunkKey = chunkPos.toLong();
        Map<Long, Long> localParents = chunkLocalParent.get(chunkKey);
        if (localParents == null || !localParents.containsKey(branchCommitId)) return false;

        Set<Long> subtree = collectSubtree(localParents, branchCommitId);
        if (subtree.contains(getChunkHeadId(chunkPos))) return false;

        List<Long> ids = chunkIndex.get(chunkKey);
        if (ids != null) ids.removeIf(subtree::contains);
        localParents.keySet().removeAll(subtree);

        // Any named branch whose recorded head for this chunk falls inside the deleted subtree
        // loses that chunk; a branch left covering no chunks (and not currently checked out) is
        // itself forgotten, so deleting a branch's commits also cleans up its name.
        Iterator<TemporalBranch> it = branches.values().iterator();
        while (it.hasNext()) {
            TemporalBranch candidate = it.next();
            if (subtree.contains(candidate.getChunkHead(chunkPos))) {
                candidate.removeChunkHead(chunkPos);
                if (candidate.getChunkHeads().isEmpty() && !candidate.getName().equals(currentBranchName)) {
                    it.remove();
                }
            }
        }
        return true;
    }

    /** rootId plus every commit transitively forked from it, per localParents' child links. */
    private static Set<Long> collectSubtree(Map<Long, Long> localParents, long rootId) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (Map.Entry<Long, Long> entry : localParents.entrySet()) {
            children.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        Set<Long> result = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            long id = stack.pop();
            if (!result.add(id)) continue;
            for (long child : children.getOrDefault(id, List.of())) stack.push(child);
        }
        return result;
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

    /**
     * Commits-since-snapshot threshold before a fresh {@link ChunkSnapshot} baseline is due; shared
     * by auto-tracking and manual Portable ChronoMarker saves.
     */
    public static final int SNAPSHOT_COMMIT_THRESHOLD = 50;

    /** Ensures chunkPos has at least one commit to anchor its history walk. */
    public boolean ensureBaseline(ChunkPos chunkPos, ServerLevel level) {
        if (!getCommitsForChunk(chunkPos).isEmpty()) return false;
        addSnapshot(level.getGameTime(), List.of(ChunkSnapshot.capture(level, chunkPos)));
        return true;
    }

    /**
     * Checks out targetGameTime for chunkPos, preferring preferredCommitId as the exact target over
     * gameTime-only resolution when it's one of chunkPos's own commits (pass {@link
     * TemporalCommit#NO_PREFERRED_COMMIT} to always resolve by gameTime alone).
     */
    @Nullable
    public CheckoutOutcome branch(ChunkPos chunkPos, long targetGameTime, long preferredCommitId, @Nullable String reuseBranchName) {
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        if (chunkCommits.isEmpty()) return null;

        long chunkKey = chunkPos.toLong();
        Map<Long, Long> localParents = chunkLocalParent.getOrDefault(chunkKey, Collections.emptyMap());
        long currentHead = chunkHeadId.getOrDefault(chunkKey, chunkCommits.get(chunkCommits.size() - 1).getId());
        long parent = resolveTarget(chunkCommits, targetGameTime, preferredCommitId);
        if (parent == currentHead) return null;

        String owner = findOwningBranch(chunkPos, parent);
        if (owner != null) {
            chunkHeadId.put(chunkKey, parent);
            currentBranchName = owner;
            return new CheckoutOutcome(null, owner, false);
        }

        if (!TemporalCommit.hasChild(localParents, parent)) {
            chunkHeadId.put(chunkKey, parent);
            return null;
        }

        String name = reuseBranchName != null ? reuseBranchName : generateBranchName();
        TemporalCommit marker = TemporalCommit.branch(nextId++, parent, targetGameTime, chunkKey);
        registerCommit(marker);
        branches.computeIfAbsent(name, n -> new TemporalBranch(n, Collections.emptyMap())).setChunkHead(chunkPos, marker.getId());
        currentBranchName = name;
        return new CheckoutOutcome(marker, name, true);
    }

    /**
     * targetGameTime resolved to a specific commit for chunkPos — preferredCommitId directly if
     * it's one of chunkPos's own commits, otherwise the nearest match by gameTime.
     */
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
        } else {
            // A real commit (not a checkout marker) advances whichever named branch is currently
            // checked out, same as git moving the current branch forward on every commit.
            TemporalBranch active = getOrCreateCurrentBranch();
            for (ChunkDelta cd : commit.getChunkDeltas()) {
                active.setChunkHead(cd.getChunkPos(), commit.getId());
            }
            for (ChunkSnapshot snapshot : commit.getChunkSnapshots()) {
                active.setChunkHead(snapshot.getChunkPos(), commit.getId());
            }
        }
    }


    /**
     * currentBranchName's TemporalBranch, lazily seeding a {@link #MAIN_BRANCH} from the current
     * {@link #chunkHeadId} map if branches hasn't been touched yet.
     */
    private TemporalBranch getOrCreateCurrentBranch() {
        TemporalBranch branch = branches.get(currentBranchName);
        if (branch == null) {
            branch = new TemporalBranch(currentBranchName, new HashMap<>(chunkHeadId));
            branches.put(currentBranchName, branch);
        }
        return branch;
    }

    public String getCurrentBranchName() {
        getOrCreateCurrentBranch();
        return currentBranchName;
    }

    /**
     * Auto-generated branch names, styled like git but without needing the player to type anything:
     * Alpha, Beta, Gamma, ...
     */
    private static final String[] BRANCH_NAME_POOL = {
            "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta", "Iota", "Kappa",
            "Lambda", "Mu", "Nu", "Xi", "Omicron", "Pi", "Rho", "Sigma", "Tau", "Upsilon", "Phi", "Chi", "Psi", "Omega"
    };
    private int nextBranchNameIndex = 0;

    private String generateBranchName() {
        String base = BRANCH_NAME_POOL[nextBranchNameIndex % BRANCH_NAME_POOL.length];
        int cycle = nextBranchNameIndex / BRANCH_NAME_POOL.length;
        nextBranchNameIndex++;
        return cycle == 0 ? base : base + (cycle + 1);
    }

    /**
     * The branch (other than currentBranchName) whose recorded lineage for chunkPos already
     * contains targetCommitId, so landing on it is a {@code git checkout <branch>} of history that
     * already exists rather than a fresh fork.
     */
    @Nullable
    private String findOwningBranch(ChunkPos chunkPos, long targetCommitId) {
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        Map<Long, Long> localParents = getLocalParentsForChunk(chunkPos);

        TemporalBranch current = getOrCreateCurrentBranch();
        long currentHead = current.getChunkHead(chunkPos);
        if (currentHead != -1L) {
            for (TemporalCommit c : TemporalCommit.ancestryChain(chunkCommits, localParents, currentHead)) {
                if (c.getId() == targetCommitId) return null;
            }
        }

        for (TemporalBranch candidate : branches.values()) {
            if (candidate.getName().equals(currentBranchName)) continue;
            long head = candidate.getChunkHead(chunkPos);
            if (head == -1L) continue;
            for (TemporalCommit c : TemporalCommit.ancestryChain(chunkCommits, localParents, head)) {
                if (c.getId() == targetCommitId) return candidate.getName();
            }
        }
        return null;
    }

    /**
     * Result of {@link #branch}: branchName/created are non-null/true only when a branch switch or
     * a fresh fork actually happened, so a caller checking out several chunks in one player action
     * can feed a freshly-created name back in as reuseBranchName for the next chunk — one jump
     * across many chunks always produces at most one new branch, instead of one per chunk.
     */
    public record CheckoutOutcome(@Nullable TemporalCommit marker, String branchName, boolean created) {}

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

    public long getLatestCommitId() {
        return commits.isEmpty() ? -1L : commits.getLast().getId();
    }

    /**
     * Every DELTA/SNAPSHOT commit in this dimension, in creation order — the dimension-wide trunk,
     * as opposed to a single chunk's local history.
     */
    public List<TemporalCommit> getTrunkCommits() {
        List<TemporalCommit> trunk = new ArrayList<>(commits.size());
        for (TemporalCommit commit : commits) {
            if (commit.getType() != TemporalCommit.Type.BRANCH) trunk.add(commit);
        }
        return trunk;
    }

    /**
     * Every commit ever registered in this dimension, trunk and every chunk's branches alike, in
     * creation order — unlike {@link #getCommitsForChunk}, nothing is left out for not touching a
     * particular chunk.
     */
    public List<TemporalCommit> getAllCommits() {
        return new ArrayList<>(commits);
    }

    /**
     * The id of chunkPos's own latest commit at or before targetGameTime, or -1 if none exists
     * (either the chunk has no history at all, or its earliest commit is still later than
     * targetGameTime).
     */
    public long resolveFloorCommit(ChunkPos chunkPos, long targetGameTime) {
        long bestId = -1L;
        long bestGameTime = Long.MIN_VALUE;
        for (TemporalCommit c : getCommitsForChunk(chunkPos)) {
            if (c.getGameTime() > targetGameTime) continue;
            if (c.getGameTime() > bestGameTime || (c.getGameTime() == bestGameTime && c.getId() > bestId)) {
                bestGameTime = c.getGameTime();
                bestId = c.getId();
            }
        }
        return bestId;
    }

    /** The commit chunkPos's live world state currently reflects, or -1 if no history yet. */
    public long getChunkHeadId(ChunkPos chunkPos) {
        long chunkKey = chunkPos.toLong();
        Long head = chunkHeadId.get(chunkKey);
        if (head != null) return head;
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        return chunkCommits.isEmpty() ? -1L : chunkCommits.get(chunkCommits.size() - 1).getId();
    }

    public boolean isEmpty() { return commits.isEmpty(); }

    /**
     * How many commits chunkPos's head is past its nearest snapshot ancestor; used to decide when a
     * fresh {@link ChunkSnapshot} is due.
     */
    public int getCommitsSinceSnapshot(ChunkPos chunkPos) {
        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        if (chunkCommits.isEmpty()) return 0;
        long headId = getChunkHeadId(chunkPos);
        Map<Long, Long> localParents = getLocalParentsForChunk(chunkPos);
        return TemporalCommit.ancestryChain(chunkCommits, localParents, headId).size();
    }

    // Manual save-point diffing (Portable ChronoMarker)

    /**
     * chunkPos's nearest SNAPSHOT ancestor (inclusive) and every DELTA between it and the head, in
     * chain order.
     */
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
     */
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

    /**
     * The block/block-entity/entity state chunkPos would have at targetGameTime, transitioning from
     * fromCommitId — built by undoing the live lineage past its fork with the target lineage, then
     * replaying the target lineage past that fork.
     */
    private record DesiredState(Map<BlockPos, BlockState> states, Map<BlockPos, CompoundTag> blockEntityTags,
                                 Map<UUID, CompoundTag> entityTags) {}

    /**
     * Result of walking chunkPos's commit graph from fromCommitId to targetCommitId over ordinary
     * DELTA/BRANCH commits — a static, world-independent function shared by the server's real jump
     * and the client's ghost-preview projection so the two can't drift apart. commonPrefixLen and
     * targetChain are exposed for the server's disjoint-lineage/SNAPSHOT fallback below.
     */
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
        Map<BlockPos, BlockState> desiredStates = new HashMap<>();
        Map<BlockPos, CompoundTag> desiredBETags = new HashMap<>();

        // If the target lineage shares no history with the live one, its chain starts fresh from
        // its own nearest SNAPSHOT ancestor — a full baseline applied first since a SNAPSHOT
        // carries no ChunkDelta for the undo/replay walk below to see.
        List<TemporalCommit> targetChain = walk.targetChain();
        if (walk.commonPrefixLen() == 0 && !targetChain.isEmpty() && targetChain.get(0).getType() == TemporalCommit.Type.SNAPSHOT) {
            for (ChunkSnapshot snapshot : targetChain.get(0).getChunkSnapshots()) {
                if (!snapshot.getChunkPos().equals(chunkPos)) continue;
                desiredStates.putAll(snapshot.toBlockStateMap());
                desiredBETags.putAll(snapshot.getBlockEntityTags());
            }
        }

        // Deltas replayed past the shared/SNAPSHOT baseline must win over it for any position they
        // touch, or every delta between the baseline and the target would be silently discarded.
        desiredStates.putAll(walk.states());
        desiredBETags.putAll(walk.blockEntityTags());

        return new DesiredState(desiredStates, desiredBETags, walk.entityTags());
    }

    /**
     * Applies the world state for chunkPos at targetGameTime, transitioning from fromCommitId (the
     * commit the live world currently reflects — capture via getChunkHeadId before calling branch()
     * for the same checkout).
     */
    public void applyChunkAtTime(ChunkPos chunkPos, long targetGameTime, long fromCommitId, ServerLevel level,
                                  long preferredCommitId, Predicate<BlockPos> isGlued) {
        applyChunkAtTime(chunkPos, targetGameTime, fromCommitId, level, preferredCommitId, isGlued, null);
    }

    /**
     * Same as {@link #applyChunkAtTime(ChunkPos, long, long, ServerLevel, long, Predicate)}, but
     * when deferredSettle is non-null the neighbor-shape/destroy settle pass is collected into it
     * instead of running immediately, so a caller applying several chunks in one batch can settle
     * them all only after every chunk in the batch has its final blocks in place — otherwise a
     * support block right at the edge of a chunk that hasn't been written yet can appear missing to
     * an already-settled neighbor's shape check.
     */
    public void applyChunkAtTime(ChunkPos chunkPos, long targetGameTime, long fromCommitId, ServerLevel level,
                                  long preferredCommitId, Predicate<BlockPos> isGlued, @Nullable List<BlockPos> deferredSettle) {
        DesiredState desired = resolveDesiredState(chunkPos, targetGameTime, fromCommitId, preferredCommitId);
        applyBlockStates(desired, level, isGlued, deferredSettle);

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

    /** Writes desired's resolved block states (and block-entity tags) into the live world. */
    private void applyBlockStates(DesiredState desired, ServerLevel level, Predicate<BlockPos> isGlued, @Nullable List<BlockPos> deferredSettle) {
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

        if (deferredSettle != null) {
            deferredSettle.addAll(changed);
        } else {
            settleBlocks(level, changed);
        }
    }

    /**
     * Settle pass: now that every position's final configuration is in place, run the shape and
     * neighbor-changed updates that {@link #applyBlockStates} deliberately skips during writing.
     */
    public static void settleBlocks(ServerLevel level, List<BlockPos> changed) {
        for (BlockPos pos : changed) {
            BlockState state = level.getBlockState(pos);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
            level.blockUpdated(pos, state.getBlock());
        }
    }

    /**
     * The energy cost of jumping chunkPos to targetGameTime from fromCommitId: costFn summed over
     * every block position whose state would actually change, plus nodeJumpSurcharge if the jump
     * resolves to a different graph node than fromCommitId (even when that node happens to carry no
     * block diff, e.g. hopping across an identical branch).
     */
    public long computeJumpCost(ChunkPos chunkPos, long targetGameTime, long fromCommitId, Level level,
                                 ToLongFunction<BlockState> costFn, long preferredCommitId, Predicate<BlockPos> isGlued,
                                 long nodeJumpSurcharge) {
        DesiredState desired = resolveDesiredState(chunkPos, targetGameTime, fromCommitId, preferredCommitId);
        long total = 0L;
        for (Map.Entry<BlockPos, BlockState> entry : desired.states().entrySet()) {
            if (isGlued.test(entry.getKey())) continue;
            BlockState desiredState = entry.getValue();
            if (!level.getBlockState(entry.getKey()).equals(desiredState)) {
                total += costFn.applyAsLong(desiredState);
            }
        }

        List<TemporalCommit> chunkCommits = getCommitsForChunk(chunkPos);
        if (!chunkCommits.isEmpty() && resolveTarget(chunkCommits, targetGameTime, preferredCommitId) != fromCommitId) {
            total += nodeJumpSurcharge;
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

        ListTag branchList = new ListTag();
        for (TemporalBranch branch : branches.values()) branchList.add(branch.toTag());
        tag.put("Branches", branchList);
        tag.putString("CurrentBranch", currentBranchName);
        tag.putInt("NextBranchNameIndex", nextBranchNameIndex);

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

        if (tag.contains("Branches", Tag.TAG_LIST)) {
            ListTag branchList = tag.getList("Branches", Tag.TAG_COMPOUND);
            for (int i = 0; i < branchList.size(); i++) {
                TemporalBranch branch = TemporalBranch.fromTag(branchList.getCompound(i));
                timeline.branches.put(branch.getName(), branch);
            }
        }
        if (tag.contains("CurrentBranch")) {
            timeline.currentBranchName = tag.getString("CurrentBranch");
        }
        timeline.nextBranchNameIndex = tag.getInt("NextBranchNameIndex");
        return timeline;
    }
}
