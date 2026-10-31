package io.github.tofithepuppycat.temporalindustries.timeline;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.function.LongConsumer;
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
    /** The most recently built jump plan; dropped whenever the history it was built from changes. */
    @Nullable private JumpPlan planCache;
    /** Bumped by every change that has to be saved, so the saver can skip repositories that have none. */
    private long revision;

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
    public void setName(String name) { this.name = name; revision++; }

    public long getRevision() { return revision; }
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

    public void addTrackedRegion(BoundingBox region) { trackedRegions.add(region); revision++; }

    public void addRegionChunk(long chunkKey) { regionChunks.add(chunkKey); revision++; }

    public void removeRegionChunk(long chunkKey) { regionChunks.remove(chunkKey); revision++; }

    /** Drops every tracked region matching toRemove; returns how many were dropped. */
    public int removeTrackedRegions(Predicate<BoundingBox> toRemove) {
        int before = trackedRegions.size();
        trackedRegions.removeIf(toRemove);
        revision++;
        return before - trackedRegions.size();
    }

    /**
     * Whether pos belongs to what this repository records and restores. Once any cuboid is glued,
     * only the glued cuboids count, in every claimed chunk. Without glue, any position of a wholly
     * claimed chunk counts, while a chunk claimed only for glue that was since removed counts for
     * nothing.
     */
    public boolean isTracked(BlockPos pos) {
        boolean regionChunk = !regionChunks.isEmpty() && regionChunks.contains(new ChunkPos(pos).toLong());
        if (!regionChunk && trackedRegions.isEmpty()) return true;
        for (BoundingBox region : trackedRegions) {
            if (region.isInside(pos)) return true;
        }
        return false;
    }

    /** Records pos's current state as its claim baseline, if it doesn't already have one. */
    public void captureClaimBaseline(ServerLevel level, ChunkPos pos) {
        if (claimBaselines.containsKey(pos.toLong())) return;
        claimBaselines.put(pos.toLong(), ChunkSnapshot.capture(level, pos));
        planCache = null;
        revision++;
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
            job.entries.put(chunkKey, ChunkEntry.full(internAgainstLatest(job.headChain, chunkKey, snapshot)));
            state.hasBaseline = true;
            state.deltaCount = 0;
            return;
        }

        List<EntityDelta> entityChanges = job.pendingEntityChanges.getOrDefault(chunkKey, Collections.emptyList());
        if (blockChanges.isEmpty() && entityChanges.isEmpty()) return;
        if (deltaDepth(job.headChain, chunkKey) >= MAX_DELTA_CHAIN) {
            job.entries.put(chunkKey, ChunkEntry.full(internAgainstLatest(job.headChain, chunkKey, snapshot)));
            state.deltaCount = 0;
            return;
        }
        job.entries.put(chunkKey, ChunkEntry.delta(blockChanges, entityChanges));
        state.deltaCount++;
    }

    /**
     * snapshot sharing every section it has in common with the chunk's most recent full state — its
     * latest FULL entry on chain, or else its claim baseline — so a re-snapshot costs only what
     * actually changed.
     */
    private ChunkSnapshot internAgainstLatest(List<RepoCommit> chain, long chunkKey, ChunkSnapshot snapshot) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            ChunkEntry e = chain.get(i).getChunkEntries().get(chunkKey);
            if (e != null && e.getType() == ChunkEntry.Type.FULL) return snapshot.internAgainst(e.getSnapshot());
        }
        ChunkSnapshot baseline = claimBaselines.get(chunkKey);
        return baseline == null ? snapshot : snapshot.internAgainst(baseline);
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
        planCache = null;
        revision++;
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

        Map<Integer, Int2ObjectMap<BlockState>> overlayBySection = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : overlay.entrySet()) {
            BlockPos pos = entry.getKey();
            int index = (pos.getY() >> 4) - snapshot.minSectionY();
            int local = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
            overlayBySection.computeIfAbsent(index, k -> new Int2ObjectOpenHashMap<>()).put(local, entry.getValue());
        }

        Map<BlockPos, CompoundTag> afterBE = snapshot.getBlockEntityTags();
        List<BlockChangeDelta> changes = new ArrayList<>();
        Set<BlockPos> changedPositions = new HashSet<>();
        ChunkPos chunkPos = new ChunkPos(chunkKey);
        for (int index : dirty) {
            BlockState[] after = snapshot.decodeSection(index);
            BlockState[] base = baseline.decodeSection(index);
            Int2ObjectMap<BlockState> sectionOverlay = overlayBySection.get(index);
            int minBlockY = (snapshot.minSectionY() + index) * 16;
            for (int local = 0; local < after.length; local++) {
                BlockState prev = sectionOverlay != null && sectionOverlay.containsKey(local)
                        ? sectionOverlay.get(local) : base[local];
                if (prev == after[local]) continue;

                BlockPos pos = new BlockPos(chunkPos.getMinBlockX() + (local & 15), minBlockY + (local >> 8),
                        chunkPos.getMinBlockZ() + ((local >> 4) & 15));
                if (!isTracked(pos)) continue;
                changes.add(new BlockChangeDelta(pos, prev, after[local], beforeBE.get(pos), afterBE.get(pos)));
                changedPositions.add(pos);
            }
        }

        // Block entities whose data changed under a block that stayed the same.
        Set<BlockPos> beOnly = new HashSet<>(beforeBE.keySet());
        beOnly.addAll(afterBE.keySet());
        for (BlockPos pos : beOnly) {
            int index = (pos.getY() >> 4) - snapshot.minSectionY();
            if (index < 0 || index >= sectionCount || changedPositions.contains(pos) || !isTracked(pos)) continue;
            CompoundTag prevTag = beforeBE.get(pos);
            CompoundTag newTag = afterBE.get(pos);
            if (Objects.equals(prevTag, newTag)) continue;
            BlockState state = snapshot.stateAt(pos);
            changes.add(new BlockChangeDelta(pos, state, state, prevTag, newTag));
        }
        return changes;
    }

    /**
     * The head's state of every block in chunk that the loaded world no longer matches, whatever
     * changed it; empty while the chunk is unloaded or has no recorded state to compare against.
     */
    public Map<BlockPos, BlockState> previewUncommittedStates(ServerLevel level, ChunkPos chunk) {
        ChunkState state = chunkStates.get(chunk.toLong());
        if (headCommitId == RepoCommit.NO_PARENT || state == null || !state.hasBaseline
                || level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
            return Map.of();
        }

        List<BlockChangeDelta> changes = diffAgainstHead(ancestryChain(headCommitId), chunk.toLong(),
                ChunkSnapshot.capture(level, chunk));
        if (changes == null) return Map.of();

        Map<BlockPos, BlockState> out = new HashMap<>();
        for (BlockChangeDelta change : changes) {
            if (!change.getPreviousState().equals(change.getNewState())) {
                out.put(change.getPos(), change.getPreviousState());
            }
        }
        return out;
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
        List<RepoCommit> chain = new ArrayList<>();
        long cur = commitId;
        while (cur != RepoCommit.NO_PARENT) {
            RepoCommit c = commits.get(cur);
            if (c == null) break;
            chain.add(c);
            cur = c.getParentId();
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * Everything about moving the head from one commit to another that does not depend on the live
     * world: the commits to undo and replay, the chunks they touch, and each chunk's resolved
     * target once it has been asked for.
     */
    static final class JumpPlan {
        final long fromCommitId;
        final long toCommitId;
        /** Commits to undo, newest first, stopping short of the lowest common ancestor. */
        final List<RepoCommit> undo;
        /** Commits to replay, oldest first. */
        final List<RepoCommit> forward;
        /** The target commit's whole ancestry, root first. */
        final List<RepoCommit> targetChain;
        /** Every chunk some commit on the path touched. */
        final Set<Long> chunks;
        private final Map<Long, ResolvedChunk> resolved = new HashMap<>();

        private JumpPlan(long fromCommitId, long toCommitId, List<RepoCommit> undo, List<RepoCommit> forward,
                         List<RepoCommit> targetChain, Set<Long> chunks) {
            this.fromCommitId = fromCommitId;
            this.toCommitId = toCommitId;
            this.undo = undo;
            this.forward = forward;
            this.targetChain = targetChain;
            this.chunks = chunks;
        }
    }

    /**
     * The plan for moving the head to targetCommitId, or null if that is where it already is or no
     * such commit exists. Plans are reused until the history changes, so a cost estimate, a preview
     * and the jump itself share one resolve per chunk.
     */
    @Nullable
    JumpPlan planJump(long targetCommitId) {
        if (targetCommitId == headCommitId || !commits.containsKey(targetCommitId)) return null;
        if (planCache != null && planCache.fromCommitId == headCommitId && planCache.toCommitId == targetCommitId) {
            return planCache;
        }

        List<RepoCommit> fromChain = ancestryChain(headCommitId);
        List<RepoCommit> toChain = ancestryChain(targetCommitId);
        int common = 0;
        int max = Math.min(fromChain.size(), toChain.size());
        while (common < max && fromChain.get(common).getId() == toChain.get(common).getId()) common++;

        List<RepoCommit> undo = new ArrayList<>(fromChain.subList(common, fromChain.size()));
        Collections.reverse(undo);
        List<RepoCommit> forward = new ArrayList<>(toChain.subList(common, toChain.size()));
        Set<Long> chunks = new HashSet<>();
        for (RepoCommit c : undo) chunks.addAll(c.getChunkEntries().keySet());
        for (RepoCommit c : forward) chunks.addAll(c.getChunkEntries().keySet());

        planCache = new JumpPlan(headCommitId, targetCommitId, undo, forward, toChain, chunks);
        return planCache;
    }

    /** chunkKey's target state under plan, resolved once and then served from the plan. */
    ResolvedChunk resolveChunk(JumpPlan plan, long chunkKey, LevelHeightAccessor height) {
        ResolvedChunk cached = plan.resolved.get(chunkKey);
        if (cached != null) return cached;
        ResolvedChunk resolved = resolveJumpDiff(plan, chunkKey, height);
        plan.resolved.put(chunkKey, resolved);
        return resolved;
    }

    /**
     * chunkKey's resolved block/block-entity state at the end of chain: the nearest FULL entry
     * for that chunk on it or — if no commit ever gave it one, meaning every commit touching it so
     * far was made before it had a baseline — its {@link #claimBaselines} snapshot from the moment
     * it was claimed, with every later entry layered over it (latest write wins, same as a
     * full/delta commit's natural git-like history).
     */
    private ResolvedChunk resolveChunkStateAt(List<RepoCommit> chain, long chunkKey) {
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
            if (baseline == null) return ResolvedChunk.NONE;
            startIndex = 0;
        }

        Map<BlockPos, BlockState> overlay = new HashMap<>();
        Map<BlockPos, CompoundTag> beTags = new HashMap<>(baseline.getBlockEntityTags());
        for (int i = startIndex; i < chain.size(); i++) {
            ChunkEntry e = chain.get(i).getChunkEntries().get(chunkKey);
            if (e == null) continue;
            for (BlockChangeDelta change : e.getBlockChanges()) {
                overlay.put(change.getPos(), change.getNewState());
                beTags.put(change.getPos(), change.getNewBlockEntityTag());
            }
        }
        return new ResolvedChunk(baseline, overlay, beTags, baseline.minSectionY(), baseline.sectionCount());
    }

    /**
     * What a jump along plan has to write for one chunk, as a sparse state: only positions some
     * commit on the path touched, mapped to their value at the target. A FULL entry anywhere on the
     * path makes the sparse form unsound, so that chunk resolves from its whole target history.
     */
    private ResolvedChunk resolveJumpDiff(JumpPlan plan, long chunkKey, LevelHeightAccessor height) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<BlockPos, CompoundTag> beTags = new HashMap<>();

        // Undo side, newest first so the oldest change's "previous" wins.
        for (RepoCommit c : plan.undo) {
            ChunkEntry e = c.getChunkEntries().get(chunkKey);
            if (e == null) continue;
            if (e.getType() == ChunkEntry.Type.FULL) return resolveChunkStateAt(plan.targetChain, chunkKey);
            for (BlockChangeDelta change : e.getBlockChanges()) {
                states.put(change.getPos(), change.getPreviousState());
                beTags.put(change.getPos(), change.getPreviousBlockEntityTag());
            }
        }

        // Replay side, oldest first so the newest change wins.
        for (RepoCommit c : plan.forward) {
            ChunkEntry e = c.getChunkEntries().get(chunkKey);
            if (e == null) continue;
            if (e.getType() == ChunkEntry.Type.FULL) return resolveChunkStateAt(plan.targetChain, chunkKey);
            for (BlockChangeDelta change : e.getBlockChanges()) {
                states.put(change.getPos(), change.getNewState());
                beTags.put(change.getPos(), change.getNewBlockEntityTag());
            }
        }
        return new ResolvedChunk(null, states, beTags, height.getMinSection(), height.getSectionsCount());
    }

    /**
     * What a jump along plan has to do to entities: every entity some commit on the path touched,
     * mapped to its recorded state at the target, or null if it must not exist there. Resolved across
     * all chunks at once, since an entity's deltas land in whichever chunk it stood in at the time
     * and a per-chunk resolve would let an older state win depending on the order the chunks happen
     * to be visited.
     */
    Map<UUID, CompoundTag> resolveJumpEntities(JumpPlan plan) {
        Map<UUID, CompoundTag> entityTags = new HashMap<>();

        // Undo side, newest first so the oldest change's "previous" wins.
        for (RepoCommit c : plan.undo) {
            for (ChunkEntry e : c.getChunkEntries().values()) {
                List<EntityDelta> changes = e.getEntityChanges();
                for (int i = changes.size() - 1; i >= 0; i--) {
                    EntityDelta change = changes.get(i);
                    entityTags.put(change.getEntityId(),
                            change.getType() == EntityDelta.Type.SPAWNED ? null : change.peekStateBefore());
                }
            }
        }

        // Replay side, oldest first so the newest change wins.
        for (RepoCommit c : plan.forward) {
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
     * Every chunk that a jump to targetCommitId could change, without resolving any of them, so a
     * caller can resolve lazily (nearest first, stopping at a budget) instead of paying for every
     * chunk of a huge claim up front.
     */
    public Set<ChunkPos> previewAffectedChunks(long targetCommitId) {
        JumpPlan plan = planJump(targetCommitId);
        if (plan == null) return Set.of();

        Set<ChunkPos> result = new HashSet<>();
        for (long chunkKey : plan.chunks) result.add(new ChunkPos(chunkKey));
        return result;
    }

    /**
     * Target block states of one chunk's positions that a jump to targetCommitId would change
     * right now; empty while the chunk is not loaded.
     */
    public Map<BlockPos, BlockState> previewChunkTargetStates(long targetCommitId, ChunkPos chunk, ServerLevel level) {
        JumpPlan plan = planJump(targetCommitId);
        LevelChunk live = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
        if (plan == null || live == null) return Map.of();

        ResolvedChunk resolved = resolveChunk(plan, chunk.toLong(), level);
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int section : resolved.sectionsToVisit()) {
            for (BlockPos pos : resolved.changesInSection(section, live, p -> false)) {
                out.put(pos, resolved.targetState(pos));
            }
        }
        return out;
    }

    /**
     * Recorded state of every entity a jump to targetCommitId would bring back or rewrite. The tags
     * are the recorded ones, uncopied: callers must treat them as read-only.
     */
    public Map<UUID, CompoundTag> previewEntityStates(long targetCommitId) {
        JumpPlan plan = planJump(targetCommitId);
        if (plan == null) return Map.of();

        Map<UUID, CompoundTag> out = new HashMap<>();
        for (Map.Entry<UUID, CompoundTag> entry : resolveJumpEntities(plan).entrySet()) {
            if (entry.getValue() != null) out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /**
     * Starts a jump of the head to targetCommitId that is carried out a slice at a time (see {@link
     * JumpJob}), or returns null if the head is already there or the commit does not exist.
     * origin is where chunks are ranked from while no player is in the level.
     */
    @Nullable
    public JumpJob beginJump(long targetCommitId, ServerLevel level, ChunkPos origin, JumpJob.Options options) {
        JumpPlan plan = planJump(targetCommitId);
        return plan == null ? null : new JumpJob(this, level, plan, origin, options);
    }

    /** Moves the head to targetCommitId once a {@link JumpJob} has rewritten the world to match it. */
    void completeJump(long targetCommitId) {
        headCommitId = targetCommitId;
        currentBranchName = null;
        for (Map.Entry<String, Long> entry : branchTips.entrySet()) {
            if (entry.getValue() == targetCommitId) { currentBranchName = entry.getKey(); break; }
        }
        planCache = null;
        revision++;
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
        planCache = null;
        revision++;
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
        planCache = null;
        revision++;

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
        ResolvedChunk state = resolveChunkStateAt(ancestryChain(commitId), pos.toLong());
        if (state == ResolvedChunk.NONE) return false;

        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        for (int section : state.sectionsToVisit()) {
            applyChanges(state, state.changesInSection(section, chunk, p -> false), level,
                    packed -> deferredSettle.add(BlockPos.of(packed)));
        }
        return true;
    }

    /** How many entities a jump rewrites and their combined health. */
    public record EntityJumpStats(int count, double health) {}

    /** The container-contents cost of a jump and the net number of items it adds (negative when it removes more than it adds). */
    public record ContainerJumpStats(long cost, long items) {
        public static final ContainerJumpStats NONE = new ContainerJumpStats(0L, 0L);
    }

    /**
     * The cost of the items a jump would add to the world's containers: per item type, the net gain
     * across every affected container in net (items merely moved between containers cost nothing),
     * charged as the item count times costFn for the item. The signed total across all items is
     * reported alongside.
     */
    public static ContainerJumpStats containerStats(Map<Item, Long> net, ToLongFunction<Item> costFn) {
        long items = 0L;
        double cost = 0.0;
        for (Map.Entry<Item, Long> entry : net.entrySet()) {
            items += entry.getValue();
            if (entry.getValue() <= 0L) continue;
            cost += (double) entry.getValue() * costFn.applyAsLong(entry.getKey());
        }
        return new ContainerJumpStats(Math.round(cost), items);
    }

    /**
     * Writes one section's worth of changes bottom-up ({@link ChunkSnapshot#BOTTOM_UP_ORDER}) with
     * {@code UPDATE_KNOWN_SHAPE}, skipping neighbor-shape/destroy cascades so a support block
     * about to be restored can't desync against a transient mid-restore state. onBlockSet receives
     * every position whose block was replaced, packed, for the caller to settle once all chunks are
     * written.
     */
    static void applyChanges(ResolvedChunk state, List<BlockPos> positions, ServerLevel level, LongConsumer onBlockSet) {
        for (BlockPos pos : positions) {
            BlockState desiredState = state.targetState(pos);
            if (!level.getBlockState(pos).equals(desiredState)) {
                level.setBlock(pos, desiredState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                onBlockSet.accept(pos.asLong());
            }

            CompoundTag beTag = state.blockEntityTag(pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null && beTag != null) {
                if (beTag.equals(be.saveWithFullMetadata(level.registryAccess()))) continue;
                CompoundTag restored = beTag.copy();
                restored.putInt("x", pos.getX());
                restored.putInt("y", pos.getY());
                restored.putInt("z", pos.getZ());
                be.loadWithComponents(restored, level.registryAccess());
                be.setChanged();
            }
        }
    }

    public static void settleBlocks(ServerLevel level, List<BlockPos> changed) {
        for (BlockPos pos : changed) settleBlock(level, pos);
    }

    static void settleBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
        level.blockUpdated(pos, state.getBlock());
    }

    /** Brings one entity to desiredTag's state, or removes it if desiredTag is null. */
    static void applyEntityState(UUID id, @Nullable CompoundTag desiredTag, ServerLevel level) {
        Entity existing = level.getEntity(id);
        if (existing != null) existing.discard();
        if (desiredTag == null) return;

        Entity restored = EntityType.loadEntityRecursive(desiredTag.copy(), level, Function.identity());
        if (restored == null) return;
        // A death is recorded at the moment health reaches zero, so a mob brought back from it
        // would otherwise die again straight away and drop its loot a second time.
        if (restored instanceof LivingEntity living && living.getHealth() <= 0.0F) {
            living.setHealth(living.getMaxHealth());
            living.deathTime = 0;
        }
        level.addFreshEntity(restored);
    }


    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        SectionPool pool = new SectionPool();
        tag.putString("Dimension", dimension.toString());
        tag.putString("Name", name);
        tag.putLong("NextId", nextId);
        tag.putLong("RootCommitId", rootCommitId);
        tag.putLong("HeadCommitId", headCommitId);
        if (currentBranchName != null) tag.putString("CurrentBranch", currentBranchName);
        tag.putInt("NextBranchNameIndex", nextBranchNameIndex);

        ListTag commitList = new ListTag();
        for (RepoCommit commit : commits.values()) commitList.add(commit.toTag(pool));
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
            entryTag.put("Snapshot", entry.getValue().toTag(pool));
            claimBaselineList.add(entryTag);
        }
        tag.put("ClaimBaselines", claimBaselineList);
        tag.put("SectionPool", pool.toTag());

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

        List<ChunkSnapshot.SnapshotSection> pool = SectionPool.fromTag(tag.getList("SectionPool", Tag.TAG_COMPOUND));
        ListTag commitList = tag.getList("Commits", Tag.TAG_COMPOUND);
        for (int i = 0; i < commitList.size(); i++) {
            RepoCommit commit = RepoCommit.fromTag(commitList.getCompound(i), pool);
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
            repo.claimBaselines.put(entryTag.getLong("Chunk"), ChunkSnapshot.fromTag(entryTag.getCompound("Snapshot"), pool));
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
