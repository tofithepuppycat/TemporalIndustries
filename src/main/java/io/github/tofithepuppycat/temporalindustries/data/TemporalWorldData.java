package io.github.tofithepuppycat.temporalindustries.data;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.timeline.BlockChangeDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.EntityDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Central server-side store: per-dimension timelines, per-player checkpoint state, tracked
 * chunks, glued regions, and pending deltas awaiting flush. Tracked chunks are rebuilt from
 * block entity onLoad() calls rather than persisted.
 */
@SuppressWarnings("null")
public class TemporalWorldData extends SavedData {
    private static final String NAME = TemporalIndustries.MODID + "_temporal";
    private static final SavedData.Factory<TemporalWorldData> FACTORY =
            new SavedData.Factory<>(TemporalWorldData::new, TemporalWorldData::load);

    private final Map<ResourceLocation, TemporalTimeline> timelines = new HashMap<>();
    private final Map<UUID, PlayerTemporalState> playerStates = new HashMap<>();

    // -------------------------------------------------------------------------
    // Per-machine repositories (new model). Persisted and keyed by machine id, independent of
    // dimension; a repository outlives its owning machine's destruction (orphaned, never deleted)
    // so a future machine claiming the same chunks could in principle resume it.
    private final Map<UUID, TemporalRepository> repositories = new HashMap<>();

    // Not persisted: rebuilt from block entity onLoad(). Which repository currently claims a given
    // chunk -- a chunk may be claimed by at most one repository at a time. Claiming only marks a
    // chunk for inclusion in that repository's next manual Commit; it never touches history, so
    // releasing a claim never discards anything (see TemporalRepository#commit).
    private final Map<ResourceLocation, Map<Long, UUID>> chunkRepoOwner = new HashMap<>();
    // Reverse index of chunkRepoOwner: repository id -> the chunks (across its one dimension) it
    // currently claims. Not persisted, kept in sync by claimChunk/releaseChunk.
    private final Map<UUID, Set<Long>> repoClaimedChunks = new HashMap<>();

    // Transient pending deltas awaiting a manual Commit, keyed by owning repository instead of
    // dimension/chunk (contrast pendingBlockDeltas/pendingEntityDeltas below, used by the old
    // per-dimension model). Never persisted: the new model has no periodic auto-flush, so an
    // uncommitted change lost on restart is an accepted tradeoff of commits being manual-only.
    private final Map<UUID, Map<Long, Map<BlockPos, BlockChangeDelta>>> repoPendingBlockDeltas = new HashMap<>();
    private final Map<UUID, Map<Long, List<EntityDelta>>> repoPendingEntityDeltas = new HashMap<>();

    // dimension -> cuboid regions marked with Temporal Glue. Glued blocks are never captured
    // into a delta and are skipped by rollback/jump, so they sit outside the timeline system.
    private final Map<ResourceLocation, List<BoundingBox>> gluedRegions = new HashMap<>();

    // Not persisted: bumped on every glue/unglue so the "Show Changes" preview can cheaply
    // notice glue changes and re-fetch instead of ghosting stale blocks.
    private int glueVersion = 0;

    // Not persisted: rebuilt when block entities load. dimension -> chunkPos.toLong() -> owners
    // currently claiming that chunk needs tracking. Owner-scoped (not a plain boolean) so one
    // owner releasing a chunk can't silently stop another owner's tracking of it.
    private final Map<ResourceLocation, Map<Long, Set<Object>>> trackedChunkOwners = new HashMap<>();

    // Transient pending deltas flushed by TemporalChangeListener on server tick.
    // Outer key: dimension. Inner key: chunkPos.toLong(). Value: latest merged delta per BlockPos.
    private final Map<ResourceLocation, Map<Long, Map<BlockPos, BlockChangeDelta>>> pendingBlockDeltas = new HashMap<>();
    // Same dimension/chunk keying as pendingBlockDeltas, scoping entity spawns/deaths to the chunk
    // they happened in.
    private final Map<ResourceLocation, Map<Long, List<EntityDelta>>> pendingEntityDeltas = new HashMap<>();

    // Not persisted: chunks a machine has claimed but hasn't been baselined yet. The actual
    // (expensive) ChunkSnapshot capture is deferred until the next flush rather than happening the
    // instant a chunk is claimed/toggled/loaded, so closing a claim GUI never itself does a full
    // chunk scan.
    private final Map<ResourceLocation, Set<Long>> pendingClaimedChunks = new HashMap<>();

    // -------------------------------------------------------------------------
    // Chrono Rift session (not persisted: an empty rift always resets to "no active session")

    /** Sentinel meaning no session is currently open (the rift is empty). */
    public static final long NO_CHRONO_RIFT_SESSION = Long.MIN_VALUE;
    private long chronoRiftActiveTime = NO_CHRONO_RIFT_SESSION;
    // Global chunk key -> the gameTime it was last filled at, so a new session (different time)
    // naturally invalidates stale chunks as players revisit them, with no bulk-reset step needed.
    private final Map<Long, Long> chronoRiftFilledChunks = new HashMap<>();
    // Chunks ChronoRiftChunkListener has seen load but not yet filled. Filling writes real blocks
    // and runs neighbor-shape "settle" updates, which can force-load bordering chunks — doing that
    // synchronously from inside ChunkEvent.Load would re-enter the event and cascade outward
    // (observed as a server hang), so loads only enqueue here; a server-tick handler drains it.
    private final LinkedHashSet<Long> chronoRiftPendingFill = new LinkedHashSet<>();
    // Chunks currently loaded in the rift dimension, maintained via ChunkEvent.Load/Unload. A chunk
    // that stays loaded across an empty-rift session boundary never gets a fresh Load event, so it
    // wouldn't otherwise notice its fill is stale for the new session's time; setChronoRiftActiveTime
    // re-queues every entry here so already-loaded chunks still get corrected.
    private final Set<Long> chronoRiftLoadedChunks = new HashSet<>();
    // Chunks that resolved to real reconstructed history the last time they were filled (as opposed
    // to being left void for lack of any commit at the requested moment). Drives the client-side rift
    // boundary shader and the server-side movement border in ChronoRiftBorderListener.
    private final Set<Long> chronoRiftTrackedChunks = new HashSet<>();

    public long getChronoRiftActiveTime() {
        return chronoRiftActiveTime;
    }

    /** Whether requestedTime may enter the rift right now: freely if it's empty, otherwise only if
     * it matches whatever moment is already open there. */
    public boolean canEnterChronoRift(ServerLevel riftLevel, long requestedTime) {
        return riftLevel.players().isEmpty() || requestedTime == chronoRiftActiveTime;
    }

    /** Opens (or re-confirms) the rift session at requestedTime. Only meaningful to call when the
     * rift was just found empty by {@link #canEnterChronoRift}. Re-queues every chunk still loaded
     * from a previous session so it gets corrected even though it won't fire a fresh Load event. */
    public void setChronoRiftActiveTime(long requestedTime) {
        chronoRiftActiveTime = requestedTime;
        chronoRiftPendingFill.addAll(chronoRiftLoadedChunks);
    }

    public void markChronoRiftChunkLoaded(ChunkPos pos) {
        chronoRiftLoadedChunks.add(pos.toLong());
    }

    public void markChronoRiftChunkUnloaded(ChunkPos pos) {
        chronoRiftLoadedChunks.remove(pos.toLong());
    }

    @Nullable
    public Long getChronoRiftChunkFilledTime(ChunkPos pos) {
        return chronoRiftFilledChunks.get(pos.toLong());
    }

    public void markChronoRiftChunkFilled(ChunkPos pos, long gameTime) {
        chronoRiftFilledChunks.put(pos.toLong(), gameTime);
    }

    public boolean isChronoRiftChunkTracked(ChunkPos pos) {
        return chronoRiftTrackedChunks.contains(pos.toLong());
    }

    public void markChronoRiftChunkTracked(ChunkPos pos, boolean tracked) {
        if (tracked) chronoRiftTrackedChunks.add(pos.toLong());
        else chronoRiftTrackedChunks.remove(pos.toLong());
    }

    public void enqueueChronoRiftFill(ChunkPos pos) {
        chronoRiftPendingFill.add(pos.toLong());
    }

    /** Pops one pending chunk off the fill queue, or null if empty. */
    @Nullable
    public ChunkPos pollChronoRiftFill() {
        Iterator<Long> it = chronoRiftPendingFill.iterator();
        if (!it.hasNext()) return null;
        long key = it.next();
        it.remove();
        return new ChunkPos(key);
    }

    // -------------------------------------------------------------------------
    // Time Dilator acceleration (not persisted: the effect is meant to be temporary, so a restart
    // simply ending it early is fine). dimension -> accelerated block pos -> ticks remaining.
    private final Map<ResourceLocation, Map<BlockPos, Integer>> acceleratedBlocks = new HashMap<>();

    /** Marks pos as accelerated for durationTicks, extending rather than shortening any effect
     * already running there. */
    public void accelerateBlock(ResourceLocation dimension, BlockPos pos, int durationTicks) {
        acceleratedBlocks.computeIfAbsent(dimension, d -> new HashMap<>())
                .merge(pos.immutable(), durationTicks, Math::max);
    }

    /** Ticks every accelerated position in dimension down by one, forgetting any that expire.
     * @return the positions that were active this tick, for the caller to re-tick. */
    public Set<BlockPos> tickAcceleratedBlocks(ResourceLocation dimension) {
        Map<BlockPos, Integer> dimMap = acceleratedBlocks.get(dimension);
        if (dimMap == null || dimMap.isEmpty()) return Collections.emptySet();

        Set<BlockPos> active = new HashSet<>(dimMap.keySet());
        Iterator<Map.Entry<BlockPos, Integer>> it = dimMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) it.remove();
            else entry.setValue(remaining);
        }
        if (dimMap.isEmpty()) acceleratedBlocks.remove(dimension);
        return active;
    }

    // -------------------------------------------------------------------------
    // Access

    public static TemporalWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    // -------------------------------------------------------------------------
    // Timeline management

    public TemporalTimeline getOrCreateTimeline(ResourceLocation dimension) {
        return timelines.computeIfAbsent(dimension, d -> new TemporalTimeline());
    }

    @Nullable
    public TemporalTimeline getTimeline(ResourceLocation dimension) {
        return timelines.get(dimension);
    }

    // -------------------------------------------------------------------------
    // Repository management (new per-machine model)

    public TemporalRepository getOrCreateRepository(UUID repoId, ResourceLocation dimension) {
        return repositories.computeIfAbsent(repoId, id -> new TemporalRepository(dimension));
    }

    @Nullable
    public TemporalRepository getRepository(UUID repoId) {
        return repositories.get(repoId);
    }

    // -------------------------------------------------------------------------
    // Chunk claim ownership (new per-machine model)

    public enum ClaimResult { CLAIMED, ALREADY_CLAIMED_BY_SELF, ALREADY_CLAIMED_ELSEWHERE }

    /** Claims chunkPos for repoId, failing if another repository already claims it. Idempotent for
     * the same repoId. */
    public ClaimResult claimChunk(ResourceLocation dimension, ChunkPos chunkPos, UUID repoId) {
        Map<Long, UUID> dimChunks = chunkRepoOwner.computeIfAbsent(dimension, d -> new HashMap<>());
        UUID existing = dimChunks.get(chunkPos.toLong());
        if (existing != null && !existing.equals(repoId)) return ClaimResult.ALREADY_CLAIMED_ELSEWHERE;
        if (existing != null) return ClaimResult.ALREADY_CLAIMED_BY_SELF;

        dimChunks.put(chunkPos.toLong(), repoId);
        repoClaimedChunks.computeIfAbsent(repoId, id -> new HashSet<>()).add(chunkPos.toLong());
        return ClaimResult.CLAIMED;
    }

    /** Releases repoId's claim on chunkPos, if it holds one. No-op otherwise. The repository's
     * commit history for this chunk is untouched -- claiming only governs future Commits. */
    public void releaseChunk(ResourceLocation dimension, ChunkPos chunkPos, UUID repoId) {
        Map<Long, UUID> dimChunks = chunkRepoOwner.get(dimension);
        if (dimChunks == null) return;
        UUID owner = dimChunks.get(chunkPos.toLong());
        if (owner == null || !owner.equals(repoId)) return;

        dimChunks.remove(chunkPos.toLong());
        if (dimChunks.isEmpty()) chunkRepoOwner.remove(dimension);

        Set<Long> claimed = repoClaimedChunks.get(repoId);
        if (claimed != null) {
            claimed.remove(chunkPos.toLong());
            if (claimed.isEmpty()) repoClaimedChunks.remove(repoId);
        }
    }

    /** Releases every chunk repoId currently claims, across every dimension -- e.g. when its
     * machine is destroyed. The repository itself (and its history) is untouched and stays
     * reachable via {@link #getRepository}. */
    public void releaseAllClaimedChunks(UUID repoId) {
        Set<Long> claimed = repoClaimedChunks.remove(repoId);
        if (claimed == null) return;
        for (Map<Long, UUID> dimChunks : chunkRepoOwner.values()) {
            dimChunks.values().removeIf(repoId::equals);
        }
        chunkRepoOwner.values().removeIf(Map::isEmpty);
    }

    @Nullable
    public UUID getClaimingRepo(ResourceLocation dimension, ChunkPos chunkPos) {
        Map<Long, UUID> dimChunks = chunkRepoOwner.get(dimension);
        return dimChunks == null ? null : dimChunks.get(chunkPos.toLong());
    }

    public Set<ChunkPos> getClaimedChunks(UUID repoId) {
        Set<Long> claimed = repoClaimedChunks.get(repoId);
        if (claimed == null) return Collections.emptySet();
        Set<ChunkPos> result = new HashSet<>();
        for (long key : claimed) result.add(new ChunkPos(key));
        return result;
    }

    // -------------------------------------------------------------------------
    // Pending delta buffering (new per-machine model), keyed by owning repository rather than
    // dimension/chunk -- mirrors recordTrackedBlockChange/recordEntityDelta below, routed via
    // getClaimingRepo once a change's chunk is known to belong to a repository.

    public void recordRepoBlockChange(UUID repoId, ChunkPos chunkPos, BlockChangeDelta delta) {
        Map<BlockPos, BlockChangeDelta> chunkMap = repoPendingBlockDeltas
                .computeIfAbsent(repoId, id -> new HashMap<>())
                .computeIfAbsent(chunkPos.toLong(), c -> new LinkedHashMap<>());

        BlockChangeDelta existing = chunkMap.get(delta.getPos());
        if (existing != null) {
            BlockChangeDelta merged = new BlockChangeDelta(
                    delta.getPos(),
                    existing.getPreviousState(),
                    delta.getNewState(),
                    existing.getPreviousBlockEntityTag(),
                    delta.getNewBlockEntityTag());
            boolean isNoOp = merged.getPreviousState().equals(merged.getNewState())
                    && Objects.equals(merged.getPreviousBlockEntityTag(), merged.getNewBlockEntityTag());
            if (isNoOp) {
                chunkMap.remove(delta.getPos());
            } else {
                chunkMap.put(delta.getPos(), merged);
            }
        } else {
            chunkMap.put(delta.getPos(), delta);
        }
    }

    public void recordRepoEntityDelta(UUID repoId, ChunkPos chunkPos, EntityDelta delta) {
        repoPendingEntityDeltas
                .computeIfAbsent(repoId, id -> new HashMap<>())
                .computeIfAbsent(chunkPos.toLong(), c -> new ArrayList<>())
                .add(delta);
    }

    /** Bundles repoId's pending changes into one new commit, including a baseline for any
     * currently-claimed chunk that doesn't have history yet. Player-triggered only -- the new
     * model has no periodic auto-flush.
     * @return the new commit, or null if there was nothing to commit */
    @Nullable
    public RepoCommit commitRepository(MinecraftServer server, UUID repoId, long gameTime, @Nullable UUID author) {
        TemporalRepository repo = repositories.get(repoId);
        if (repo == null) return null;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, repo.getDimension()));
        if (level == null) return null;

        Map<Long, Map<BlockPos, BlockChangeDelta>> pendingBlocks = repoPendingBlockDeltas.getOrDefault(repoId, Collections.emptyMap());
        Map<Long, List<EntityDelta>> pendingEntities = repoPendingEntityDeltas.getOrDefault(repoId, Collections.emptyMap());

        Map<Long, Map<BlockPos, BlockChangeDelta>> commitBlocks = new HashMap<>(pendingBlocks);
        for (ChunkPos claimed : getClaimedChunks(repoId)) {
            if (!repo.hasBaseline(claimed)) {
                commitBlocks.computeIfAbsent(claimed.toLong(), k -> Collections.emptyMap());
            }
        }

        RepoCommit result = repo.commit(level, commitBlocks, pendingEntities, gameTime, author);
        repoPendingBlockDeltas.remove(repoId);
        repoPendingEntityDeltas.remove(repoId);
        if (result != null) setDirty();
        return result;
    }

    // -------------------------------------------------------------------------
    // Chunk tracking

    /** Queues chunkPos for a baseline snapshot (if it doesn't already have history) at the next
     * flush, instead of capturing a {@link io.github.tofithepuppycat.temporalindustries.timeline.ChunkSnapshot}
     * immediately. Cheap — no world access happens until the flush actually drains this. */
    public void markChunkPending(ResourceLocation dimension, ChunkPos pos) {
        pendingClaimedChunks.computeIfAbsent(dimension, d -> new HashSet<>()).add(pos.toLong());
    }

    /** Releases owner's claim on chunkPos. The chunk stays tracked if any other owner still claims
     * it — only the last owner letting go actually stops tracking. */
    public void untrackChunk(ResourceLocation dimension, ChunkPos pos, Object owner) {
        Map<Long, Set<Object>> dimChunks = trackedChunkOwners.get(dimension);
        if (dimChunks == null) return;
        Set<Object> owners = dimChunks.get(pos.toLong());
        if (owners == null) return;
        owners.remove(owner);
        if (owners.isEmpty()) dimChunks.remove(pos.toLong());
        if (dimChunks.isEmpty()) trackedChunkOwners.remove(dimension);
    }

    public boolean isTracked(ResourceLocation dimension, ChunkPos pos) {
        Map<Long, Set<Object>> dimChunks = trackedChunkOwners.get(dimension);
        return dimChunks != null && dimChunks.containsKey(pos.toLong());
    }

    // -------------------------------------------------------------------------
    // Glued regions

    public void addGluedRegion(ResourceLocation dimension, BoundingBox region) {
        gluedRegions.computeIfAbsent(dimension, d -> new ArrayList<>()).add(region);
        glueVersion++;
        setDirty();
    }

    /** Removes every glued region whose bounding box the segment from origin to end passes through.
     * @return how many were removed. */
    public int removeGluedRegionsAlongRay(ResourceLocation dimension, Vec3 origin, Vec3 end) {
        List<BoundingBox> regions = gluedRegions.get(dimension);
        if (regions == null) return 0;

        int before = regions.size();
        regions.removeIf(region -> regionAabb(region).clip(origin, end).isPresent());
        int removed = before - regions.size();
        if (removed > 0) {
            if (regions.isEmpty()) gluedRegions.remove(dimension);
            glueVersion++;
            setDirty();
        }
        return removed;
    }

    private static AABB regionAabb(BoundingBox region) {
        return new AABB(region.minX(), region.minY(), region.minZ(),
                region.maxX() + 1, region.maxY() + 1, region.maxZ() + 1);
    }

    public boolean isGlued(ResourceLocation dimension, BlockPos pos) {
        List<BoundingBox> regions = gluedRegions.get(dimension);
        if (regions == null) return false;
        for (BoundingBox region : regions) {
            if (region.isInside(pos)) return true;
        }
        return false;
    }

    public List<BoundingBox> getGluedRegions(ResourceLocation dimension) {
        return gluedRegions.getOrDefault(dimension, Collections.emptyList());
    }

    /** Cheap fingerprint that changes on every glue/unglue, anywhere. */
    public int getGlueVersion() {
        return glueVersion;
    }

    // -------------------------------------------------------------------------
    // Player states

    public PlayerTemporalState getOrCreatePlayerState(UUID playerId) {
        return playerStates.computeIfAbsent(playerId, PlayerTemporalState::new);
    }

    @Nullable
    public PlayerTemporalState getPlayerState(UUID playerId) {
        return playerStates.get(playerId);
    }

    // -------------------------------------------------------------------------
    // Pending delta buffering

    /**
     * Records a block change for a tracked chunk, merging with any pending change at the same
     * position (keeping the original previousState) and dropping the entry if the net effect is
     * a no-op.
     */
    public void recordTrackedBlockChange(ResourceLocation dimension, ChunkPos chunkPos, BlockChangeDelta delta) {
        Map<BlockPos, BlockChangeDelta> chunkMap = pendingBlockDeltas
                .computeIfAbsent(dimension, d -> new HashMap<>())
                .computeIfAbsent(chunkPos.toLong(), c -> new LinkedHashMap<>());

        BlockChangeDelta existing = chunkMap.get(delta.getPos());
        if (existing != null) {
            BlockChangeDelta merged = new BlockChangeDelta(
                    delta.getPos(),
                    existing.getPreviousState(),
                    delta.getNewState(),
                    existing.getPreviousBlockEntityTag(),
                    delta.getNewBlockEntityTag());
            boolean isNoOp = merged.getPreviousState().equals(merged.getNewState())
                    && Objects.equals(merged.getPreviousBlockEntityTag(), merged.getNewBlockEntityTag());
            if (isNoOp) {
                chunkMap.remove(delta.getPos());
            } else {
                chunkMap.put(delta.getPos(), merged);
            }
        } else {
            chunkMap.put(delta.getPos(), delta);
        }
    }

    public void recordEntityDelta(ResourceLocation dimension, ChunkPos chunkPos, EntityDelta delta) {
        pendingEntityDeltas
                .computeIfAbsent(dimension, d -> new HashMap<>())
                .computeIfAbsent(chunkPos.toLong(), c -> new ArrayList<>())
                .add(delta);
    }

    /**
     * Drains every chunk queued by {@link #markChunkPending}, giving chunks that don't already have
     * history a baseline snapshot. All baselines queued for the same dimension in this flush (e.g.
     * a whole rectangle/freehand claim) land as a single {@link TemporalTimeline#addSnapshot} commit
     * on the branch currently checked out, instead of one commit per chunk. Called at the start of
     * every flush so a claimed-but-not-yet-committed chunk's expensive capture happens here instead
     * of at claim time.
     */
    private void drainPendingClaims(MinecraftServer server) {
        if (pendingClaimedChunks.isEmpty()) return;

        for (Map.Entry<ResourceLocation, Set<Long>> entry : pendingClaimedChunks.entrySet()) {
            ResourceLocation dimension = entry.getKey();
            ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimension));
            if (level == null) continue;

            TemporalTimeline timeline = getOrCreateTimeline(dimension);
            List<ChunkSnapshot> baselines = new ArrayList<>();
            for (long chunkKey : entry.getValue()) {
                ChunkPos chunkPos = new ChunkPos(chunkKey);
                if (timeline.getCommitsForChunk(chunkPos).isEmpty()) {
                    baselines.add(ChunkSnapshot.capture(level, chunkPos));
                }
            }
            if (!baselines.isEmpty()) {
                timeline.addSnapshot(level.getGameTime(), baselines);
                setDirty();
            }
        }
        pendingClaimedChunks.clear();
    }

    /**
     * Flushes all pending deltas into commits on the relevant timelines, first draining any chunks
     * queued by {@link #markChunkPending} into baseline snapshots.
     *
     * @return true if any commits were created
     */
    public boolean flushPendingDeltas(MinecraftServer server, long gameTime) {
        return flushPendingDeltas(server, gameTime, false);
    }

    /** @param playerMarked whether this flush was explicitly requested by a player (e.g. a
     * Chronosphere's "Commit" button) rather than the automatic periodic flush, flagging the
     * resulting DELTA so the graph can render it like a Portable ChronoMarker save. */
    public boolean flushPendingDeltas(MinecraftServer server, long gameTime, boolean playerMarked) {
        drainPendingClaims(server);
        if (pendingBlockDeltas.isEmpty() && pendingEntityDeltas.isEmpty()) return false;

        Set<ResourceLocation> allDims = new HashSet<>(pendingBlockDeltas.keySet());
        allDims.addAll(pendingEntityDeltas.keySet());

        for (ResourceLocation dimension : allDims) {
            TemporalTimeline timeline = getOrCreateTimeline(dimension);

            Map<Long, Map<BlockPos, BlockChangeDelta>> dimBlockPending = pendingBlockDeltas.get(dimension);
            Map<Long, List<EntityDelta>> dimEntityPending = pendingEntityDeltas.get(dimension);

            Set<Long> allChunks = new HashSet<>();
            if (dimBlockPending != null) allChunks.addAll(dimBlockPending.keySet());
            if (dimEntityPending != null) allChunks.addAll(dimEntityPending.keySet());

            List<ChunkDelta> chunkDeltas = new ArrayList<>();
            for (long chunkKey : allChunks) {
                Map<BlockPos, BlockChangeDelta> blockChanges = dimBlockPending != null
                        ? dimBlockPending.getOrDefault(chunkKey, Collections.emptyMap())
                        : Collections.emptyMap();
                List<EntityDelta> entityChanges = dimEntityPending != null
                        ? dimEntityPending.getOrDefault(chunkKey, Collections.emptyList())
                        : Collections.emptyList();
                if (blockChanges.isEmpty() && entityChanges.isEmpty()) continue;

                ChunkPos chunkPos = new ChunkPos(chunkKey);
                chunkDeltas.add(new ChunkDelta(dimension, chunkPos,
                        new ArrayList<>(blockChanges.values()), entityChanges));
            }

            if (!chunkDeltas.isEmpty()) {
                timeline.addDelta(gameTime, chunkDeltas, playerMarked);
            }
        }

        pendingBlockDeltas.clear();
        pendingEntityDeltas.clear();
        setDirty();
        return true;
    }

    /** On-demand version of {@link #flushPendingDeltas}, for a player-triggered "Commit" action
     * (e.g. the Chronosphere GUI) instead of waiting for the next automatic flush tick. Always
     * flags the resulting commit {@code playerMarked}, same as a Portable ChronoMarker save. */
    public boolean commitNow(MinecraftServer server, long gameTime) {
        return flushPendingDeltas(server, gameTime, true);
    }

    // -------------------------------------------------------------------------
    // Persistence

    public static TemporalWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        TemporalWorldData data = new TemporalWorldData();

        ListTag timelineList = tag.getList("Timelines", Tag.TAG_COMPOUND);
        for (int i = 0; i < timelineList.size(); i++) {
            CompoundTag tTag = timelineList.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(tTag.getString("Dimension"));
            data.timelines.put(dim, TemporalTimeline.fromTag(tTag.getCompound("Timeline")));
        }

        ListTag playerList = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerList.size(); i++) {
            PlayerTemporalState state = PlayerTemporalState.fromTag(playerList.getCompound(i));
            data.playerStates.put(state.getPlayerId(), state);
        }

        ListTag repoList = tag.getList("Repositories", Tag.TAG_COMPOUND);
        for (int i = 0; i < repoList.size(); i++) {
            CompoundTag repoTag = repoList.getCompound(i);
            UUID id = repoTag.getUUID("Id");
            data.repositories.put(id, TemporalRepository.fromTag(repoTag.getCompound("Repository")));
        }

        ListTag glueList = tag.getList("GluedRegions", Tag.TAG_COMPOUND);
        for (int i = 0; i < glueList.size(); i++) {
            CompoundTag gTag = glueList.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(gTag.getString("Dimension"));
            BoundingBox region = new BoundingBox(
                    gTag.getInt("MinX"), gTag.getInt("MinY"), gTag.getInt("MinZ"),
                    gTag.getInt("MaxX"), gTag.getInt("MaxY"), gTag.getInt("MaxZ"));
            data.gluedRegions.computeIfAbsent(dim, d -> new ArrayList<>()).add(region);
        }

        // trackedChunks rebuilt from block entity onLoad() — not persisted.
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag timelineList = new ListTag();
        for (Map.Entry<ResourceLocation, TemporalTimeline> entry : timelines.entrySet()) {
            CompoundTag tTag = new CompoundTag();
            tTag.putString("Dimension", entry.getKey().toString());
            tTag.put("Timeline", entry.getValue().toTag());
            timelineList.add(tTag);
        }
        tag.put("Timelines", timelineList);

        ListTag playerList = new ListTag();
        for (PlayerTemporalState state : playerStates.values()) {
            if (state.isArmed() || state.getChronoRiftOrigin() != null) playerList.add(state.toTag());
        }
        tag.put("Players", playerList);

        ListTag repoList = new ListTag();
        for (Map.Entry<UUID, TemporalRepository> entry : repositories.entrySet()) {
            CompoundTag repoTag = new CompoundTag();
            repoTag.putUUID("Id", entry.getKey());
            repoTag.put("Repository", entry.getValue().toTag());
            repoList.add(repoTag);
        }
        tag.put("Repositories", repoList);

        ListTag glueList = new ListTag();
        for (Map.Entry<ResourceLocation, List<BoundingBox>> entry : gluedRegions.entrySet()) {
            for (BoundingBox region : entry.getValue()) {
                CompoundTag gTag = new CompoundTag();
                gTag.putString("Dimension", entry.getKey().toString());
                gTag.putInt("MinX", region.minX());
                gTag.putInt("MinY", region.minY());
                gTag.putInt("MinZ", region.minZ());
                gTag.putInt("MaxX", region.maxX());
                gTag.putInt("MaxY", region.maxY());
                gTag.putInt("MaxZ", region.maxZ());
                glueList.add(gTag);
            }
        }
        tag.put("GluedRegions", glueList);

        return tag;
    }
}
