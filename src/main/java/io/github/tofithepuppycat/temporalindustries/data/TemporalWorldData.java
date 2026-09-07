package io.github.tofithepuppycat.temporalindustries.data;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.timeline.BlockChangeDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.EntityDelta;
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
    // Chunk tracking

    /** Registers owner as wanting chunkPos tracked. Idempotent per owner, so a single matching
     * untrackChunk always fully releases it. Also ensures the chunk has a baseline snapshot
     * ({@link TemporalTimeline#ensureBaseline}). */
    public void trackChunk(ResourceLocation dimension, ChunkPos pos, Object owner, ServerLevel level) {
        trackedChunkOwners.computeIfAbsent(dimension, d -> new HashMap<>())
                .computeIfAbsent(pos.toLong(), c -> new HashSet<>())
                .add(owner);
        if (getOrCreateTimeline(dimension).ensureBaseline(pos, level)) {
            setDirty();
        }
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

    /** Releases every chunk owner currently claims, across every dimension. */
    public void untrackAllForOwner(Object owner) {
        for (Map<Long, Set<Object>> dimChunks : trackedChunkOwners.values()) {
            dimChunks.values().removeIf(owners -> {
                owners.remove(owner);
                return owners.isEmpty();
            });
        }
        trackedChunkOwners.values().removeIf(Map::isEmpty);
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

    public void removePlayerState(UUID playerId) {
        playerStates.remove(playerId);
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
     * Flushes all pending deltas into commits on the relevant timelines.
     *
     * @return true if any commits were created
     */
    public boolean flushPendingDeltas(long gameTime) {
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
                timeline.addDelta(gameTime, chunkDeltas);
            }
        }

        pendingBlockDeltas.clear();
        pendingEntityDeltas.clear();
        setDirty();
        return true;
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
            if (state.isArmed()) playerList.add(state.toTag());
        }
        tag.put("Players", playerList);

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
