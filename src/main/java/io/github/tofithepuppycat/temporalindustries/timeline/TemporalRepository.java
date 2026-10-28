package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * One machine's repository: a tree of {@link RepoCommit}s (single parent per commit, root at {@link
 * #rootCommitId}), with one {@link #headCommitId} tracking the commit currently rendered in-world.
 */
public final class TemporalRepository {
    /**
     * Auto-generated branch names, styled like git but without needing the player to type anything:
     * Alpha, Beta, Gamma, ...
     */
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
    /**
     * chunkKey -> that chunk's state the instant it was first claimed, captured eagerly rather than
     * lazily at the chunk's first commit.
     */
    private final Map<Long, ChunkSnapshot> claimBaselines = new HashMap<>();
    /** Branch name -> the commit it currently points to. */
    private final Map<String, Long> branchTips = new HashMap<>();
    @Nullable private String currentBranchName = null;
    private int nextBranchNameIndex = 0;

    /**
     * Cuboids that Temporal Glue marked for this repository. Chunks claimed only for these (see
     * {@link #regionChunks}) are tracked inside the cuboids alone.
     */
    private final List<BoundingBox> trackedRegions = new ArrayList<>();
    /** Chunks claimed solely to cover {@link #trackedRegions}; every other claimed chunk is tracked whole. */
    private final Set<Long> regionChunks = new HashSet<>();

    /** The player-facing handle a Chronosphere uses to find this repository again. */
    private String name = "";

    public TemporalRepository(ResourceLocation dimension) {
        this.dimension = dimension;
    }

    public ResourceLocation getDimension() { return dimension; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
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

    public List<BoundingBox> getTrackedRegions() { return Collections.unmodifiableList(trackedRegions); }

    public Set<Long> getRegionChunks() { return Collections.unmodifiableSet(regionChunks); }

    public void addTrackedRegion(BoundingBox region) { trackedRegions.add(region); }

    public void addRegionChunk(long chunkKey) { regionChunks.add(chunkKey); }

    public void removeRegionChunk(long chunkKey) { regionChunks.remove(chunkKey); }

    /** Drops every tracked region matching toRemove; returns how many were dropped. */
    public int removeTrackedRegions(Predicate<BoundingBox> toRemove) {
        int before = trackedRegions.size();
        trackedRegions.removeIf(toRemove);
        return before - trackedRegions.size();
    }

    /**
     * Whether pos belongs to what this repository records and restores: any position of a wholly
     * claimed chunk, but only the glued cuboids within a chunk claimed for them alone.
     */
    public boolean isTracked(BlockPos pos) {
        if (regionChunks.isEmpty() || !regionChunks.contains(new ChunkPos(pos).toLong())) return true;
        for (BoundingBox region : trackedRegions) {
            if (region.isInside(pos)) return true;
        }
        return false;
    }

    /** Records pos's current state as its claim baseline, if it doesn't already have one. */
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


    /**
     * A chunk's delta chain is cut with a fresh FULL once it reaches this many deltas, bounding how
     * many entries {@link #resolveChunkStateAt} ever replays.
     */
    private static final int MAX_DELTA_CHAIN = 32;

    /**
     * Bundles pendingBlockChanges/pendingEntityChanges (both keyed by chunkPos.toLong()) into one
     * new commit attached as a child of the current head.
     */
    @Nullable
    public RepoCommit commit(ServerLevel level, Map<Long, Map<BlockPos, BlockChangeDelta>> pendingBlockChanges,
                              Map<Long, List<EntityDelta>> pendingEntityChanges, long gameTime, @Nullable UUID author) {
        CommitJob job = beginCommit(level, pendingBlockChanges, pendingEntityChanges, gameTime, author);
        if (job == null) return null;
        while (!stepCommit(job, Long.MAX_VALUE)) { }
        return finishCommit(job);
    }

    /**
     * A commit being assembled one chunk at a time, so a large repository doesn't have to be
     * captured within a single tick. The head must stay put until {@link #finishCommit} runs.
     */
    public static final class CommitJob {
        private final ServerLevel level;
        private final Deque<Long> remaining;
        private final int total;
        private final Map<Long, List<EntityDelta>> pendingEntityChanges;
        private final Map<Long, ChunkEntry> entries = new HashMap<>();
        private final List<RepoCommit> headChain;
        private final boolean firstCommit;
        private final long gameTime;
        @Nullable private final UUID author;

        private CommitJob(ServerLevel level, Collection<Long> chunkKeys, Map<Long, List<EntityDelta>> pendingEntityChanges,
                          List<RepoCommit> headChain, boolean firstCommit, long gameTime, @Nullable UUID author) {
            this.level = level;
            this.remaining = new ArrayDeque<>(chunkKeys);
            this.total = chunkKeys.size();
            this.pendingEntityChanges = pendingEntityChanges;
            this.headChain = headChain;
            this.firstCommit = firstCommit;
            this.gameTime = gameTime;
            this.author = author;
        }

        /** Fraction of the chunks already captured, 0 to 1. */
        public float progress() {
            return total == 0 ? 1.0F : (total - remaining.size()) / (float) total;
        }
    }

    /** Starts a commit of the chunks pendingBlockChanges/pendingEntityChanges name, or null if there are none. */
    @Nullable
    public CommitJob beginCommit(ServerLevel level, Map<Long, Map<BlockPos, BlockChangeDelta>> pendingBlockChanges,
                                 Map<Long, List<EntityDelta>> pendingEntityChanges, long gameTime, @Nullable UUID author) {
        Set<Long> chunkKeys = new LinkedHashSet<>();
        chunkKeys.addAll(pendingBlockChanges.keySet());
        chunkKeys.addAll(pendingEntityChanges.keySet());
        if (chunkKeys.isEmpty()) return null;

        boolean firstCommit = headCommitId == RepoCommit.NO_PARENT;
        return new CommitJob(level, chunkKeys, pendingEntityChanges, ancestryChain(headCommitId), firstCommit, gameTime, author);
    }

    /**
     * Captures chunks until deadlineNanos ({@link System#nanoTime} scale) has passed, always at
     * least one. Returns whether every chunk is done.
     */
    public boolean stepCommit(CommitJob job, long deadlineNanos) {
        do {
            Long chunkKey = job.remaining.poll();
            if (chunkKey == null) break;
            captureChunkForCommit(job, chunkKey);
        } while (!job.remaining.isEmpty() && System.nanoTime() < deadlineNanos);
        return job.remaining.isEmpty();
    }

    private void captureChunkForCommit(CommitJob job, long chunkKey) {
        ChunkState state = chunkStates.computeIfAbsent(chunkKey, k -> new ChunkState());
        ChunkSnapshot snapshot = ChunkSnapshot.capture(job.level, new ChunkPos(chunkKey));

        List<BlockChangeDelta> blockChanges = job.firstCommit || !state.hasBaseline
                ? null : diffAgainstHead(job.headChain, chunkKey, snapshot);
        if (blockChanges == null) {
            job.entries.put(chunkKey, ChunkEntry.full(snapshot));
            state.hasBaseline = true;
            state.deltaCount = 0;
            return;
        }

        List<EntityDelta> entityChanges = job.pendingEntityChanges.getOrDefault(chunkKey, Collections.emptyList());
        if (blockChanges.isEmpty() && entityChanges.isEmpty()) return;
        if (deltaDepth(job.headChain, chunkKey) >= MAX_DELTA_CHAIN) {
            job.entries.put(chunkKey, ChunkEntry.full(snapshot));
            state.deltaCount = 0;
            return;
        }
        job.entries.put(chunkKey, ChunkEntry.delta(blockChanges, entityChanges));
        state.deltaCount++;
    }

    /** Turns a job whose chunks are all captured into the new head commit, or null if nothing changed. */
    @Nullable
    public RepoCommit finishCommit(CommitJob job) {
        Map<Long, ChunkEntry> entries = job.entries;
        long gameTime = job.gameTime;
        UUID author = job.author;
        if (entries.isEmpty()) return null;

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

    /**
     * Number of consecutive DELTA entries for chunkKey on commitId's ancestry, newest first,
     * stopping at the nearest FULL entry.
     */
    private static int deltaDepth(List<RepoCommit> chain, long chunkKey) {
        int depth = 0;
        for (int i = chain.size() - 1; i >= 0; i--) {
            ChunkEntry e = chain.get(i).getChunkEntries().get(chunkKey);
            if (e == null) continue;
            if (e.getType() == ChunkEntry.Type.FULL) break;
            depth++;
        }
        return depth;
    }

    /**
     * Every block position whose state or block entity data differs between chunkKey's state at
     * the end of chain and the freshly captured snapshot, with the old values as "previous"; null
     * if the chunk has no recorded state to compare against.
     * <p>
     * The old state is its baseline snapshot plus the sparse deltas recorded after it, so only
     * sections that differ from the baseline or hold a delta position are expanded and compared.
     */
    @Nullable
    private List<BlockChangeDelta> diffAgainstHead(List<RepoCommit> chain, long chunkKey, ChunkSnapshot snapshot) {
        int fullIndex = -1;
        for (int i = chain.size() - 1; i >= 0; i--) {
            ChunkEntry e = chain.get(i).getChunkEntries().get(chunkKey);
            if (e != null && e.getType() == ChunkEntry.Type.FULL) { fullIndex = i; break; }
        }

        ChunkSnapshot baseline;
        if (fullIndex >= 0) {
            baseline = chain.get(fullIndex).getChunkEntries().get(chunkKey).getSnapshot();
        } else {
            baseline = claimBaselines.get(chunkKey);
            if (baseline == null) return null;
        }

        Map<BlockPos, BlockState> overlay = new HashMap<>();
        Map<BlockPos, CompoundTag> beforeBE = new HashMap<>(baseline.getBlockEntityTags());
        for (int i = fullIndex + 1; i < chain.size(); i++) {
            ChunkEntry e = chain.get(i).getChunkEntries().get(chunkKey);
            if (e == null) continue;
            for (BlockChangeDelta change : e.getBlockChanges()) {
                overlay.put(change.getPos(), change.getNewState());
                beforeBE.put(change.getPos(), change.getNewBlockEntityTag());
            }
        }

        int sectionCount = snapshot.sectionCount();
        Set<Integer> dirty = new TreeSet<>();
        for (int i = 0; i < sectionCount; i++) {
            if (!baseline.sectionSameBlocks(snapshot, i)) dirty.add(i);
        }
        for (BlockPos pos : overlay.keySet()) {
            int index = (pos.getY() >> 4) - snapshot.minSectionY();
            if (index >= 0 && index < sectionCount) dirty.add(index);
        }

        Map<BlockPos, CompoundTag> afterBE = snapshot.getBlockEntityTags();
        List<BlockChangeDelta> changes = new ArrayList<>();
        for (int index : dirty) {
            Map<BlockPos, BlockState> after = new HashMap<>();
            Map<BlockPos, BlockState> base = new HashMap<>();
            snapshot.collectSectionInto(index, after);
            baseline.collectSectionInto(index, base);
            for (Map.Entry<BlockPos, BlockState> entry : after.entrySet()) {
                BlockPos pos = entry.getKey();
                if (!isTracked(pos)) continue;
                BlockState prev = overlay.getOrDefault(pos, base.get(pos));
                CompoundTag prevTag = beforeBE.get(pos);
                CompoundTag newTag = afterBE.get(pos);
                if (prev.equals(entry.getValue()) && Objects.equals(prevTag, newTag)) continue;
                changes.add(new BlockChangeDelta(pos, prev, entry.getValue(), prevTag, newTag));
            }
        }

        // Block entities that changed inside a section whose blocks did not.
        Set<BlockPos> beOnly = new HashSet<>(beforeBE.keySet());
        beOnly.addAll(afterBE.keySet());
        for (BlockPos pos : beOnly) {
            int index = (pos.getY() >> 4) - snapshot.minSectionY();
            if (index < 0 || index >= sectionCount || dirty.contains(index) || !isTracked(pos)) continue;
            CompoundTag prevTag = beforeBE.get(pos);
            CompoundTag newTag = afterBE.get(pos);
            if (Objects.equals(prevTag, newTag)) continue;
            BlockState state = snapshot.stateAt(pos);
            changes.add(new BlockChangeDelta(pos, state, state, prevTag, newTag));
        }
        return changes;
    }

    /**
     * Ensures this repository has at least a root commit, baselining every chunk in chunks that
     * doesn't already have one.
     */
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


    /** commitId's ancestry, root-first, commitId last. */
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

    /**
     * The lowest common ancestor of a and b, or {@link RepoCommit#NO_PARENT} if they share none
     * (shouldn't occur within one repository's single-rooted tree, barring an invalid id).
     */
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

    /**
     * Every chunk key touched by any commit strictly between fromExclusive and toInclusive, walking
     * toInclusive's parent chain.
     */
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

    private record ResolvedChunkState(Map<BlockPos, BlockState> states, Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntityTags) {
        static final ResolvedChunkState EMPTY = new ResolvedChunkState(Map.of(), Map.of());
    }

    /**
     * chunkKey's resolved block/block-entity state at commitId: walks commitId's ancestry
     * back to the nearest FULL entry for that chunk, or — if no commit ever gave it one, meaning
     * every commit touching it so far was made before it had a baseline — falls back to its {@link
     * #claimBaselines} snapshot from the moment it was claimed, then overlays every entry after
     * that point up to commitId (latest write wins, same as a full/delta commit's natural git-like
     * history).
     */
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
            }
        }
        return new ResolvedChunkState(states, beTags);
    }

    /**
     * What a jump from fromCommitId to toCommitId has to write for one chunk, as a sparse state:
     * only positions some commit on the path between the two touched, mapped to their value at
     * toCommitId.
     */
    private ResolvedChunkState resolveJumpDiff(long fromCommitId, long toCommitId, long lca, long chunkKey) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<BlockPos, CompoundTag> beTags = new HashMap<>();

        // Undo side, newest first so the oldest change's "previous" wins.
        long cur = fromCommitId;
        while (cur != lca && cur != RepoCommit.NO_PARENT) {
            RepoCommit c = commits.get(cur);
            if (c == null) return resolveChunkStateAt(toCommitId, chunkKey);
            ChunkEntry e = c.getChunkEntries().get(chunkKey);
            if (e != null) {
                if (e.getType() == ChunkEntry.Type.FULL) return resolveChunkStateAt(toCommitId, chunkKey);
                for (BlockChangeDelta change : e.getBlockChanges()) {
                    states.put(change.getPos(), change.getPreviousState());
                    beTags.put(change.getPos(), change.getPreviousBlockEntityTag());
                }
            }
            cur = c.getParentId();
        }

        // Replay side, oldest first so the newest change wins.
        LinkedList<RepoCommit> forward = new LinkedList<>();
        cur = toCommitId;
        while (cur != lca && cur != RepoCommit.NO_PARENT) {
            RepoCommit c = commits.get(cur);
            if (c == null) return resolveChunkStateAt(toCommitId, chunkKey);
            forward.addFirst(c);
            cur = c.getParentId();
        }
        for (RepoCommit c : forward) {
            ChunkEntry e = c.getChunkEntries().get(chunkKey);
            if (e == null) continue;
            if (e.getType() == ChunkEntry.Type.FULL) return resolveChunkStateAt(toCommitId, chunkKey);
            for (BlockChangeDelta change : e.getBlockChanges()) {
                states.put(change.getPos(), change.getNewState());
                beTags.put(change.getPos(), change.getNewBlockEntityTag());
            }
        }
        return new ResolvedChunkState(states, beTags);
    }

    /**
     * What a jump from fromCommitId to toCommitId has to do to entities: every entity some commit on
     * the path touched, mapped to its recorded state at toCommitId, or null if it must not exist
     * there. Resolved across all chunks at once, since an entity's deltas land in whichever chunk it
     * stood in at the time and a per-chunk resolve would let an older state win depending on the
     * order the chunks happen to be visited.
     */
    private Map<UUID, CompoundTag> resolveJumpEntities(long fromCommitId, long toCommitId, long lca) {
        Map<UUID, CompoundTag> entityTags = new HashMap<>();

        // Undo side, newest first so the oldest change's "previous" wins.
        long cur = fromCommitId;
        while (cur != lca && cur != RepoCommit.NO_PARENT) {
            RepoCommit c = commits.get(cur);
            if (c == null) break;
            for (ChunkEntry e : c.getChunkEntries().values()) {
                List<EntityDelta> changes = e.getEntityChanges();
                for (int i = changes.size() - 1; i >= 0; i--) {
                    EntityDelta change = changes.get(i);
                    entityTags.put(change.getEntityId(),
                            change.getType() == EntityDelta.Type.SPAWNED ? null : change.peekStateBefore());
                }
            }
            cur = c.getParentId();
        }

        // Replay side, oldest first so the newest change wins.
        LinkedList<RepoCommit> forward = new LinkedList<>();
        cur = toCommitId;
        while (cur != lca && cur != RepoCommit.NO_PARENT) {
            RepoCommit c = commits.get(cur);
            if (c == null) break;
            forward.addFirst(c);
            cur = c.getParentId();
        }
        for (RepoCommit c : forward) {
            for (ChunkEntry e : c.getChunkEntries().values()) {
                for (EntityDelta change : e.getEntityChanges()) {
                    entityTags.put(change.getEntityId(),
                            change.getType() == EntityDelta.Type.REMOVED ? null : change.peekStateAfter());
                }
            }
        }
        return entityTags;
    }

    /**
     * Every chunk whose world state could change by moving the head from fromCommitId to
     * targetCommitId — i.e. touched by some commit on either side of their lowest common ancestor.
     */
    private Set<Long> chunksAffectedByJump(long fromCommitId, long targetCommitId) {
        long lca = findLowestCommonAncestor(fromCommitId, targetCommitId);
        Set<Long> touched = new HashSet<>(touchedChunksBetween(lca, fromCommitId));
        touched.addAll(touchedChunksBetween(lca, targetCommitId));
        return touched;
    }


    /**
     * Every chunk affected by jumping from the current head to targetCommitId, mapped to that
     * chunk's fully resolved target block states.
     */
    public Map<ChunkPos, Map<BlockPos, BlockState>> previewJumpTargetStates(long targetCommitId) {
        Map<ChunkPos, Map<BlockPos, BlockState>> result = new HashMap<>();
        for (ChunkPos chunk : previewAffectedChunks(targetCommitId)) {
            result.put(chunk, previewChunkTargetStates(targetCommitId, chunk));
        }
        return result;
    }

    /**
     * Just the chunk set of {@link #previewJumpTargetStates}, without resolving any of them, so a
     * caller can resolve lazily (nearest first, stopping at a budget) instead of paying for every
     * chunk of a huge claim up front.
     */
    public Set<ChunkPos> previewAffectedChunks(long targetCommitId) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return Set.of();

        Set<ChunkPos> result = new HashSet<>();
        for (long chunkKey : chunksAffectedByJump(headCommitId, targetCommitId)) {
            result.add(new ChunkPos(chunkKey));
        }
        return result;
    }

    /** One chunk's resolved target block states; see {@link #previewJumpTargetStates}. */
    public Map<BlockPos, BlockState> previewChunkTargetStates(long targetCommitId, ChunkPos chunk) {
        return resolveJumpDiff(headCommitId, targetCommitId, findLowestCommonAncestor(headCommitId, targetCommitId),
                chunk.toLong()).states();
    }


    /**
     * Deletes commitId and every commit descended from it. Refused for the root and for any commit
     * on the head's ancestry (head included), since the world currently reflects that line.
     */
    public boolean deleteCommit(long commitId) {
        RepoCommit target = commits.get(commitId);
        if (target == null) return false;

        for (RepoCommit c : ancestryChain(headCommitId)) {
            if (c.getId() == commitId) return false;
        }

        Map<Long, List<Long>> children = new HashMap<>();
        for (RepoCommit c : commits.values()) {
            children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
        }
        Set<Long> subtree = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(commitId);
        while (!stack.isEmpty()) {
            long id = stack.pop();
            if (!subtree.add(id)) continue;
            for (long child : children.getOrDefault(id, List.of())) stack.push(child);
        }

        long parentId = target.getParentId();
        for (long id : subtree) {
            commits.remove(id);
            childCount.remove(id);
        }
        Integer count = childCount.get(parentId);
        if (count != null) {
            if (count <= 1) childCount.remove(parentId);
            else childCount.put(parentId, count - 1);
        }

        // A branch left pointing into the deleted subtree is forgotten, except the checked-out one,
        // which falls back to the deleted commit's parent.
        Iterator<Map.Entry<String, Long>> it = branchTips.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (!subtree.contains(entry.getValue())) continue;
            if (entry.getKey().equals(currentBranchName)) entry.setValue(parentId);
            else it.remove();
        }

        rebuildChunkStates();
        return true;
    }

    /**
     * Wipes this repository back to empty and immediately re-baselines every chunk in
     * chunksToRebaseline from live world state as a fresh root commit — the live world is never
     * touched, only the recorded history.
     */
    public void reset(ServerLevel level, long gameTime, @Nullable UUID author, Collection<ChunkPos> chunksToRebaseline) {
        commits.clear();
        childCount.clear();
        chunkStates.clear();
        claimBaselines.clear();
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

    /**
     * Recomputes every chunk's hasBaseline/deltaCount from the current head's ancestry, after
     * commits have been removed.
     */
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


    /**
     * The commit one step forward from the head: the head's child on the current branch's line if
     * it has one, otherwise its most recent child; {@link RepoCommit#NO_PARENT} if the head has
     * no children.
     */
    public long nextCommitAfterHead() {
        Set<Long> branchLine = new HashSet<>();
        Long tip = currentBranchName == null ? null : branchTips.get(currentBranchName);
        if (tip != null) {
            for (RepoCommit c : ancestryChain(tip)) branchLine.add(c.getId());
        }

        RepoCommit best = null;
        for (RepoCommit c : commits.values()) {
            if (c.getParentId() != headCommitId) continue;
            boolean better = best == null;
            if (!better) {
                boolean onLine = branchLine.contains(c.getId());
                boolean bestOnLine = branchLine.contains(best.getId());
                better = onLine != bestOnLine ? onLine : c.getId() > best.getId();
            }
            if (better) best = c;
        }
        return best == null ? RepoCommit.NO_PARENT : best.getId();
    }

    /**
     * The tip of the current branch, or the commit with the latest gameTime if no branch is
     * checked out; {@link RepoCommit#NO_PARENT} for an empty repository.
     */
    public long latestCommitId() {
        Long tip = currentBranchName == null ? null : branchTips.get(currentBranchName);
        if (tip != null) return tip;

        RepoCommit latest = null;
        for (RepoCommit c : commits.values()) {
            if (latest == null || c.getGameTime() > latest.getGameTime()
                    || (c.getGameTime() == latest.getGameTime() && c.getId() > latest.getId())) {
                latest = c;
            }
        }
        return latest == null ? RepoCommit.NO_PARENT : latest.getId();
    }

    /**
     * The latest commit (highest gameTime at or before targetGameTime, newest id on a tie) that
     * recorded an entry for pos, or null if there is none.
     */
    @Nullable
    public RepoCommit latestCommitTouching(ChunkPos pos, long targetGameTime) {
        long key = pos.toLong();
        RepoCommit best = null;
        for (RepoCommit c : commits.values()) {
            if (c.getGameTime() > targetGameTime || !c.getChunkEntries().containsKey(key)) continue;
            if (best == null || c.getGameTime() > best.getGameTime()
                    || (c.getGameTime() == best.getGameTime() && c.getId() > best.getId())) {
                best = c;
            }
        }
        return best;
    }

    /**
     * Writes pos's block state as of commitId into level, without moving the head. Returns false,
     * writing nothing, when the chunk has no recorded state at that commit.
     */
    public boolean paintChunkAt(long commitId, ChunkPos pos, ServerLevel level, List<BlockPos> deferredSettle) {
        ResolvedChunkState state = resolveChunkStateAt(commitId, pos.toLong());
        if (state == ResolvedChunkState.EMPTY) return false;
        applyBlockStates(state.states(), state.blockEntityTags(), level, p -> false, deferredSettle);
        return true;
    }

    /**
     * Moves the head to targetCommitId and updates every in-world chunk affected by the move (see
     * {@link #chunksAffectedByJump}); chunks the move doesn't touch are left as rendered.
     */
    public List<BlockPos> jumpTo(long targetCommitId, ServerLevel level, Predicate<BlockPos> isGlued,
                                 boolean restoreEntities) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return List.of();

        long lca = findLowestCommonAncestor(headCommitId, targetCommitId);
        Set<Long> affectedChunks = chunksAffectedByJump(headCommitId, targetCommitId);
        List<BlockPos> deferredSettle = new ArrayList<>();

        for (long chunkKey : affectedChunks) {
            ResolvedChunkState desired = resolveJumpDiff(headCommitId, targetCommitId, lca, chunkKey);
            applyBlockStates(desired.states(), desired.blockEntityTags(), level, isGlued, deferredSettle);
        }

        settleBlocks(level, deferredSettle);
        if (restoreEntities) applyEntityStates(resolveJumpEntities(headCommitId, targetCommitId, lca), level);

        headCommitId = targetCommitId;
        currentBranchName = null;
        for (Map.Entry<String, Long> entry : branchTips.entrySet()) {
            if (entry.getValue() == targetCommitId) { currentBranchName = entry.getKey(); break; }
        }
        return deferredSettle;
    }

    /** How many entities a jump rewrites and their combined health. */
    public record EntityJumpStats(int count, double health) {}

    /**
     * The entities a jump to targetCommitId would restore, remove or revert, with the health each
     * would have afterwards (or, for a removal, that the live entity has now).
     */
    public EntityJumpStats computeEntityJumpStats(long targetCommitId, ServerLevel level) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return new EntityJumpStats(0, 0.0);

        Map<UUID, net.minecraft.nbt.CompoundTag> desiredEntities = resolveJumpEntities(headCommitId, targetCommitId,
                findLowestCommonAncestor(headCommitId, targetCommitId));

        double health = 0.0;
        for (Map.Entry<UUID, net.minecraft.nbt.CompoundTag> entry : desiredEntities.entrySet()) {
            net.minecraft.nbt.CompoundTag tag = entry.getValue();
            if (tag != null) {
                health += tag.getFloat("Health");
            } else if (level.getEntity(entry.getKey()) instanceof LivingEntity living) {
                health += living.getHealth();
            }
        }
        return new EntityJumpStats(desiredEntities.size(), health);
    }

    /**
     * The block-change cost of jumping to targetCommitId: costFn summed over every block position
     * (across every affected chunk) whose state would actually change.
     */
    public long computeJumpCost(long targetCommitId, ServerLevel level, ToLongFunction<BlockState> costFn,
                                 Predicate<BlockPos> isGlued) {
        return computeJumpCost(targetCommitId, level, costFn, isGlued, false);
    }

    /**
     * As above; with loadedChunksOnly, chunks that aren't currently loaded are left out of the sum
     * instead of being force-loaded by getBlockState.
     */
    public long computeJumpCost(long targetCommitId, ServerLevel level, ToLongFunction<BlockState> costFn,
                                 Predicate<BlockPos> isGlued, boolean loadedChunksOnly) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return 0L;

        long total = 0L;
        long lca = findLowestCommonAncestor(headCommitId, targetCommitId);
        for (long chunkKey : chunksAffectedByJump(headCommitId, targetCommitId)) {
            if (loadedChunksOnly && !level.hasChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey))) continue;
            ResolvedChunkState desired = resolveJumpDiff(headCommitId, targetCommitId, lca, chunkKey);
            for (Map.Entry<BlockPos, BlockState> entry : desired.states().entrySet()) {
                if (isGlued.test(entry.getKey())) continue;
                if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                    total += costFn.applyAsLong(entry.getValue());
                }
            }
        }
        return total;
    }

    /** The container-contents cost of a jump and the number of stack slots it was charged for. */
    public record ContainerJumpStats(long cost, double slots) {
        public static final ContainerJumpStats NONE = new ContainerJumpStats(0L, 0.0);
    }

    /**
     * The cost of the items a jump to targetCommitId would add to the world's containers: per item
     * type, the net gain across every affected container (items merely moved between containers
     * cost nothing), charged as stack slots (count / max stack size) times costFn for the item.
     * Loaded-only mode skips unloaded chunks instead of force-loading them.
     */
    public ContainerJumpStats computeContainerJumpStats(long targetCommitId, ServerLevel level, ToLongFunction<Item> costFn,
                                                        Predicate<BlockPos> isGlued, boolean loadedChunksOnly) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return ContainerJumpStats.NONE;

        Map<Item, Long> net = new HashMap<>();
        long lca = findLowestCommonAncestor(headCommitId, targetCommitId);
        for (long chunkKey : chunksAffectedByJump(headCommitId, targetCommitId)) {
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            if (loadedChunksOnly && !level.hasChunk(chunkX, chunkZ)) continue;

            ResolvedChunkState desired = resolveJumpDiff(headCommitId, targetCommitId, lca, chunkKey);
            for (Map.Entry<BlockPos, CompoundTag> entry : desired.blockEntityTags().entrySet()) {
                if (entry.getValue() == null || isGlued.test(entry.getKey())) continue;
                ContainerContents.tally(entry.getValue(), net, 1);
            }
            for (Map.Entry<BlockPos, BlockEntity> entry : level.getChunk(chunkX, chunkZ).getBlockEntities().entrySet()) {
                BlockPos pos = entry.getKey();
                if (!desired.states().containsKey(pos) || isGlued.test(pos)) continue;
                ContainerContents.tally(entry.getValue().saveWithoutMetadata(level.registryAccess()), net, -1);
            }
        }

        double slots = 0.0;
        double cost = 0.0;
        for (Map.Entry<Item, Long> entry : net.entrySet()) {
            if (entry.getValue() <= 0L) continue;
            double itemSlots = entry.getValue() / (double) Math.max(1, entry.getKey().getDefaultMaxStackSize());
            slots += itemSlots;
            cost += itemSlots * costFn.applyAsLong(entry.getKey());
        }
        return new ContainerJumpStats(Math.round(cost), slots);
    }

    /**
     * Two passes, bottom-up ({@link ChunkSnapshot#BOTTOM_UP_ORDER}): the first writes every changed
     * block with {@code UPDATE_KNOWN_SHAPE}, skipping neighbor-shape/destroy cascades so a support
     * block about to be restored can't desync against a transient mid-restore state.
     */
    private void applyBlockStates(Map<BlockPos, BlockState> states, Map<BlockPos, net.minecraft.nbt.CompoundTag> beTags,
                                   ServerLevel level, Predicate<BlockPos> isGlued, List<BlockPos> deferredSettle) {
        // A FULL snapshot resolves to every position in the chunk, so only positions whose block
        // differs from the world or that carry block entity data are worth sorting and visiting.
        List<BlockPos> positions = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : states.entrySet()) {
            BlockPos pos = entry.getKey();
            if (isGlued.test(pos)) continue;
            if (beTags.get(pos) != null || !level.getBlockState(pos).equals(entry.getValue())) positions.add(pos);
        }
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
                if (beTag.equals(be.saveWithFullMetadata(level.registryAccess()))) continue;
                net.minecraft.nbt.CompoundTag restored = beTag.copy();
                restored.putInt("x", pos.getX());
                restored.putInt("y", pos.getY());
                restored.putInt("z", pos.getZ());
                be.loadWithComponents(restored, level.registryAccess());
                be.setChanged();
            }
        }
    }

    public static void settleBlocks(ServerLevel level, List<BlockPos> changed) {
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
                Entity restored = EntityType.loadEntityRecursive(desiredTag.copy(), level, Function.identity());
                if (restored == null) continue;
                // A death is recorded at the moment health reaches zero, so a mob brought back from it
                // would otherwise die again straight away and drop its loot a second time.
                if (restored instanceof LivingEntity living && living.getHealth() <= 0.0F) {
                    living.setHealth(living.getMaxHealth());
                    living.deathTime = 0;
                }
                level.addFreshEntity(restored);
            }
        }
    }


    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.toString());
        tag.putString("Name", name);
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

        ListTag regionList = new ListTag();
        for (BoundingBox region : trackedRegions) {
            regionList.add(new IntArrayTag(new int[] {
                    region.minX(), region.minY(), region.minZ(), region.maxX(), region.maxY(), region.maxZ()}));
        }
        tag.put("TrackedRegions", regionList);
        tag.put("RegionChunks", new LongArrayTag(regionChunks.stream().mapToLong(Long::longValue).toArray()));

        return tag;
    }

    public static TemporalRepository fromTag(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null) dimension = net.minecraft.world.level.Level.OVERWORLD.location();
        TemporalRepository repo = new TemporalRepository(dimension);
        repo.name = tag.getString("Name");
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

        ListTag regionList = tag.getList("TrackedRegions", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < regionList.size(); i++) {
            int[] box = regionList.getIntArray(i);
            if (box.length == 6) repo.trackedRegions.add(new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]));
        }
        for (long key : tag.getLongArray("RegionChunks")) repo.regionChunks.add(key);

        return repo;
    }
}
