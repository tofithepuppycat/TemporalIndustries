package io.github.tofithepuppycat.temporalindustries.data;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.timeline.BlockChangeDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.EntityDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
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
 * Central server-side store: repositories, per-player checkpoint state, glued regions, and the
 * Chrono Rift session.
 */
@SuppressWarnings("null")
public class TemporalWorldData extends SavedData {
    private static final String NAME = TemporalIndustries.MODID + "_temporal";
    private static final SavedData.Factory<TemporalWorldData> FACTORY =
            new SavedData.Factory<>(TemporalWorldData::new, TemporalWorldData::load);

    private final Map<UUID, PlayerTemporalState> playerStates = new HashMap<>();

    private final Map<UUID, TemporalRepository> repositories = new HashMap<>();
    // Persisted: repository id -> the machine currently using it. Absent means orphaned.
    private final Map<UUID, GlobalPos> repositoryOwners = new HashMap<>();

    // Not persisted: rebuilt from block entity onLoad().
    private final Map<ResourceLocation, Map<Long, UUID>> chunkRepoOwner = new HashMap<>();
    // Reverse index of chunkRepoOwner: repository id -> the chunks (across its one dimension) it
    // currently claims. Not persisted, kept in sync by claimChunk/releaseChunk.
    private final Map<UUID, Set<Long>> repoClaimedChunks = new HashMap<>();

    // Transient pending deltas awaiting a manual Commit, keyed by owning repository.
    private final Map<UUID, Map<Long, Map<BlockPos, BlockChangeDelta>>> repoPendingBlockDeltas = new HashMap<>();
    private final Map<UUID, Map<Long, List<EntityDelta>>> repoPendingEntityDeltas = new HashMap<>();

    // dimension -> cuboid regions marked with Temporal Glue. Glued blocks are never captured
    // into a delta and are skipped by rollback/jump, so they sit outside the timeline system.
    private final Map<ResourceLocation, List<BoundingBox>> gluedRegions = new HashMap<>();

    // Not persisted: bumped on every glue/unglue so the "Show Changes" preview can cheaply
    // notice glue changes and re-fetch instead of ghosting stale blocks.
    private int glueVersion = 0;

    /** Sentinel meaning no session is currently open (the rift is empty). */
    public static final long NO_CHRONO_RIFT_SESSION = Long.MIN_VALUE;
    private long chronoRiftActiveTime = NO_CHRONO_RIFT_SESSION;
    // Global chunk key -> the gameTime it was last filled at, so a new session (different time)
    // naturally invalidates stale chunks as players revisit them, with no bulk-reset step needed.
    private final Map<Long, Long> chronoRiftFilledChunks = new HashMap<>();
    // Chunks ChronoRiftChunkListener has seen load but not yet filled.
    private final LinkedHashSet<Long> chronoRiftPendingFill = new LinkedHashSet<>();
    // Chunks currently loaded in the rift dimension, maintained via ChunkEvent.Load/Unload.
    private final Set<Long> chronoRiftLoadedChunks = new HashSet<>();
    // Chunks that resolved to real reconstructed history the last time they were filled (as opposed
    // to being left void for lack of any commit at the requested moment).
    private final Set<Long> chronoRiftTrackedChunks = new HashSet<>();

    public long getChronoRiftActiveTime() {
        return chronoRiftActiveTime;
    }

    /**
     * Whether requestedTime may enter the rift right now: freely if it's empty, otherwise only if
     * it matches whatever moment is already open there.
     */
    public boolean canEnterChronoRift(ServerLevel riftLevel, long requestedTime) {
        return riftLevel.players().isEmpty() || requestedTime == chronoRiftActiveTime;
    }

    /** Opens (or re-confirms) the rift session at requestedTime. */
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

    private final Map<ResourceLocation, Map<BlockPos, Integer>> acceleratedBlocks = new HashMap<>();

    /**
     * Marks pos as accelerated for durationTicks, extending rather than shortening any effect
     * already running there.
     */
    public void accelerateBlock(ResourceLocation dimension, BlockPos pos, int durationTicks) {
        acceleratedBlocks.computeIfAbsent(dimension, d -> new HashMap<>())
                .merge(pos.immutable(), durationTicks, Math::max);
    }

    /** Ticks every accelerated position in dimension down by one, forgetting any that expire. */
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


    public static TemporalWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }


    /** Every repository recording history for dimension. */
    public List<TemporalRepository> getRepositoriesIn(ResourceLocation dimension) {
        List<TemporalRepository> result = new ArrayList<>();
        for (TemporalRepository repo : repositories.values()) {
            if (repo.getDimension().equals(dimension)) result.add(repo);
        }
        return result;
    }


    public TemporalRepository getOrCreateRepository(UUID repoId, ResourceLocation dimension) {
        return repositories.computeIfAbsent(repoId, id -> {
            TemporalRepository repo = new TemporalRepository(dimension);
            repo.setName(defaultRepositoryName(id));
            setDirty();
            return repo;
        });
    }

    @Nullable
    public TemporalRepository getRepository(UUID repoId) {
        return repositories.get(repoId);
    }

    public static final int MAX_REPOSITORY_NAME_LENGTH = 32;

    /** Name given to a repository nobody has named, derived from its id so it never collides. */
    private static String defaultRepositoryName(UUID repoId) {
        return "repository-" + repoId.toString().substring(0, 8);
    }

    /**
     * A name as entered by a player, stripped of surrounding whitespace; null if it's empty or too
     * long.
     */
    @Nullable
    public static String normalizeRepositoryName(String raw) {
        String name = raw.trim();
        if (name.isEmpty() || name.length() > MAX_REPOSITORY_NAME_LENGTH) return null;
        return name;
    }

    /** The repository named name (ignoring case), or null if there isn't one. */
    @Nullable
    public UUID findRepositoryByName(String name) {
        for (Map.Entry<UUID, TemporalRepository> entry : repositories.entrySet()) {
            if (entry.getValue().getName().equalsIgnoreCase(name)) return entry.getKey();
        }
        return null;
    }

    /**
     * Names of the unowned repositories in dimension that contain query (ignoring case), those
     * starting with it first, then alphabetically; at most limit of them.
     */
    public List<String> suggestAvailableRepositoryNames(String query, ResourceLocation dimension, int limit) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.of();
        List<String> matches = new ArrayList<>();
        for (Map.Entry<UUID, TemporalRepository> entry : repositories.entrySet()) {
            TemporalRepository repo = entry.getValue();
            if (repositoryOwners.containsKey(entry.getKey()) || !repo.getDimension().equals(dimension)) continue;
            if (repo.getName().toLowerCase(Locale.ROOT).contains(needle)) matches.add(repo.getName());
        }
        matches.sort(Comparator
                .comparing((String name) -> !name.toLowerCase(Locale.ROOT).startsWith(needle))
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        return matches.size() > limit ? new ArrayList<>(matches.subList(0, limit)) : matches;
    }

    /** Creates a fresh, empty repository called name. */
    public UUID createRepository(String name, ResourceLocation dimension) {
        UUID id = UUID.randomUUID();
        TemporalRepository repo = new TemporalRepository(dimension);
        repo.setName(name);
        repositories.put(id, repo);
        setDirty();
        return id;
    }

    /** Records the machine currently using repoId. */
    public void setRepositoryOwner(UUID repoId, GlobalPos machine) {
        if (machine.equals(repositoryOwners.put(repoId, machine))) return;
        setDirty();
    }

    /** Marks repoId as orphaned: its machine is gone, so it can be bound again by name. */
    public void clearRepositoryOwner(UUID repoId) {
        if (repositoryOwners.remove(repoId) != null) setDirty();
    }

    /**
     * Permanently removes repoId, its history, its owner record, its chunk claims and any
     * uncommitted changes recorded for it.
     */
    public void deleteRepository(UUID repoId) {
        releaseAllClaimedChunks(repoId);
        repoPendingBlockDeltas.remove(repoId);
        repoPendingEntityDeltas.remove(repoId);
        repositoryOwners.remove(repoId);
        repositories.remove(repoId);
        setDirty();
    }

    public boolean hasRepositoryOwner(UUID repoId) {
        return repositoryOwners.containsKey(repoId);
    }


    public enum ClaimResult { CLAIMED, ALREADY_CLAIMED_BY_SELF, ALREADY_CLAIMED_ELSEWHERE }

    /** Claims chunkPos for repoId, failing if another repository already claims it. */
    public ClaimResult claimChunk(ResourceLocation dimension, ChunkPos chunkPos, UUID repoId) {
        Map<Long, UUID> dimChunks = chunkRepoOwner.computeIfAbsent(dimension, d -> new HashMap<>());
        UUID existing = dimChunks.get(chunkPos.toLong());
        if (existing != null && !existing.equals(repoId)) return ClaimResult.ALREADY_CLAIMED_ELSEWHERE;
        if (existing != null) return ClaimResult.ALREADY_CLAIMED_BY_SELF;

        dimChunks.put(chunkPos.toLong(), repoId);
        repoClaimedChunks.computeIfAbsent(repoId, id -> new HashSet<>()).add(chunkPos.toLong());
        return ClaimResult.CLAIMED;
    }

    /** Releases repoId's claim on chunkPos, if it holds one. */
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

    /**
     * Releases every chunk repoId currently claims, across every dimension -- e.g. when its machine
     * is destroyed.
     */
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

    /** Whether repoId records and restores pos; see {@link TemporalRepository#isTracked}. */
    public boolean isTrackedBy(UUID repoId, BlockPos pos) {
        TemporalRepository repo = repositories.get(repoId);
        return repo == null || repo.isTracked(pos);
    }

    public Set<ChunkPos> getClaimedChunks(UUID repoId) {
        Set<Long> claimed = repoClaimedChunks.get(repoId);
        if (claimed == null) return Collections.emptySet();
        Set<ChunkPos> result = new HashSet<>();
        for (long key : claimed) result.add(new ChunkPos(key));
        return result;
    }


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

    /**
     * Bundles repoId's pending changes into one new commit, including a baseline for any currently-
     * claimed chunk that doesn't have history yet.
     */
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
        // Every claimed chunk is diffed against its stored state, not just those with recorded
        // events: edits that bypass the block events (bulk tools, commands) would otherwise be missed.
        for (ChunkPos claimed : getClaimedChunks(repoId)) {
            commitBlocks.computeIfAbsent(claimed.toLong(), k -> Collections.emptyMap());
        }

        RepoCommit result = repo.commit(level, commitBlocks, pendingEntities, gameTime, author);
        repoPendingBlockDeltas.remove(repoId);
        repoPendingEntityDeltas.remove(repoId);
        if (result != null) setDirty();
        return result;
    }


    public void addGluedRegion(ResourceLocation dimension, BoundingBox region) {
        gluedRegions.computeIfAbsent(dimension, d -> new ArrayList<>()).add(region);
        glueVersion++;
        setDirty();
    }

    /**
     * Removes every glued region whose bounding box the segment from origin to end passes through.
     */
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


    public PlayerTemporalState getOrCreatePlayerState(UUID playerId) {
        return playerStates.computeIfAbsent(playerId, PlayerTemporalState::new);
    }

    @Nullable
    public PlayerTemporalState getPlayerState(UUID playerId) {
        return playerStates.get(playerId);
    }


    public static TemporalWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        TemporalWorldData data = new TemporalWorldData();

        ListTag playerList = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerList.size(); i++) {
            PlayerTemporalState state = PlayerTemporalState.fromTag(playerList.getCompound(i));
            data.playerStates.put(state.getPlayerId(), state);
        }

        ListTag repoList = tag.getList("Repositories", Tag.TAG_COMPOUND);
        for (int i = 0; i < repoList.size(); i++) {
            CompoundTag repoTag = repoList.getCompound(i);
            UUID id = repoTag.getUUID("Id");
            TemporalRepository repo = TemporalRepository.fromTag(repoTag.getCompound("Repository"));
            if (repo.getName().isEmpty()) repo.setName(defaultRepositoryName(id));
            data.repositories.put(id, repo);
        }

        ListTag ownerList = tag.getList("RepositoryOwners", Tag.TAG_COMPOUND);
        for (int i = 0; i < ownerList.size(); i++) {
            CompoundTag ownerTag = ownerList.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(ownerTag.getString("Dimension"));
            if (dim == null) continue;
            data.repositoryOwners.put(ownerTag.getUUID("Id"), GlobalPos.of(
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim),
                    BlockPos.of(ownerTag.getLong("Pos"))));
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

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
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

        ListTag ownerList = new ListTag();
        for (Map.Entry<UUID, GlobalPos> entry : repositoryOwners.entrySet()) {
            CompoundTag ownerTag = new CompoundTag();
            ownerTag.putUUID("Id", entry.getKey());
            ownerTag.putString("Dimension", entry.getValue().dimension().location().toString());
            ownerTag.putLong("Pos", entry.getValue().pos().asLong());
            ownerList.add(ownerTag);
        }
        tag.put("RepositoryOwners", ownerList);

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
