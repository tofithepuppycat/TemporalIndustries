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
 * Central server-side store. Replaces TemporalAnchorSavedData.
 *
 * Holds:
 *   - Per-dimension TemporalTimeline (all commits for tracked chunks)
 *   - Per-player PlayerTemporalState (anchor checkpoints and recorded changes)
 *   - The set of currently tracked chunks (rebuilt from block entity onLoad() calls,
 *     not persisted — chunks re-register themselves on world load)
 *   - Transient pending deltas that accumulate between flush intervals
 */
@SuppressWarnings("null")
public class TemporalWorldData extends SavedData {
    private static final String NAME = TemporalIndustries.MODID + "_temporal";
    private static final SavedData.Factory<TemporalWorldData> FACTORY =
            new SavedData.Factory<>(TemporalWorldData::new, TemporalWorldData::load);

    private final Map<ResourceLocation, TemporalTimeline> timelines = new HashMap<>();
    private final Map<UUID, PlayerTemporalState> playerStates = new HashMap<>();

    // dimension -> cuboid regions marked with Temporal Glue (see item.TemporalGlueItem). Glued
    // blocks are never captured into a delta (TemporalChangeListener#shouldRecord) and are skipped
    // when a rollback/jump would otherwise overwrite them (TemporalTimeline's isGlued predicate
    // param), so they sit outside the timeline system entirely. Persisted like timelines.
    private final Map<ResourceLocation, List<BoundingBox>> gluedRegions = new HashMap<>();

    // Not persisted: bumped on every glue/unglue. Gluing never creates a timeline commit (see
    // above), so nothing else changes a chunk's head id when a region is glued/unglued — the
    // in-world "Show Changes" preview (TimelineProjectionManager) needs this as a separate cheap
    // fingerprint to notice glue changes and re-fetch, otherwise it keeps ghosting blocks a jump
    // would actually skip (or vice versa) until the player re-opens the GUI.
    private int glueVersion = 0;

    // Not persisted: rebuilt when block entities load. dimension -> chunkPos.toLong() -> the set
    // of owners currently claiming that chunk needs tracking (a TimeMachineBlockEntity/
    // ChronosphereBlockEntity keyed by its own BlockPos, or a held Portable ChronoMarker keyed by
    // the wielder's UUID). Owner-scoped rather than a plain boolean set so one owner letting go of
    // a chunk (auto-tracking toggled off, claim released, machine broken) can never silently stop
    // another owner's tracking of that same chunk out from under it — seen in practice as deltas
    // still being recorded for a chunk right after its Chronosphere's auto-tracking was switched
    // off, because a Portable ChronoMarker (or another machine) sharing that chunk was still
    // tracking it. Keyed by dimension too: chunk (x, z) coordinates collide across dimensions, and
    // a raw ChunkPos-only key would let an unrelated chunk in another dimension enable/disable
    // tracking here by coincidence of coordinates.
    private final Map<ResourceLocation, Map<Long, Set<Object>>> trackedChunkOwners = new HashMap<>();

    // Transient pending deltas flushed by TemporalChangeListener on server tick.
    // Outer key: dimension. Inner key: chunkPos.toLong(). Value: latest merged delta per BlockPos.
    private final Map<ResourceLocation, Map<Long, Map<BlockPos, BlockChangeDelta>>> pendingBlockDeltas = new HashMap<>();
    // Same dimension/chunk keying as pendingBlockDeltas, so entity spawns/deaths end up scoped to
    // the chunk they happened in instead of bundled indiscriminately into every commit that
    // dimension ever produces.
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

    /** Registers owner as wanting chunkPos (in dimension) tracked. Idempotent per owner: calling
     * this repeatedly for the same owner (e.g. every tick a Portable ChronoMarker refreshes its
     * radius) never grows past one claim, so a single matching untrackChunk always fully releases it.
     * Also guarantees chunkPos has a baseline snapshot to anchor its history walk (see
     * {@link TemporalTimeline#ensureBaseline}) — centralized here so every owner gets this for free
     * instead of each call site having to remember to pair the two calls itself. */
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

    /** Releases every chunk owner currently claims, across every dimension — for a stateful owner
     * (a held Portable ChronoMarker, keyed by player UUID) that needs to fully let go without
     * tracking which chunks it last touched itself. */
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

    /** Removes every glued region whose bounding box the segment from origin to end passes through
     * — used to unglue by aiming roughly at a region rather than needing to click an exact block
     * inside it (see item.TemporalGlueItem#deleteRegionsAlongSight). @return how many were removed. */
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

    /** Cheap fingerprint that changes on every glue/unglue, anywhere — see {@link #glueVersion}. */
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
     * Records a block change for a tracked chunk. When the same position is
     * changed multiple times within one flush interval the deltas are merged:
     * the original previousState is preserved and only newState is updated.
     * If the net effect is no change (same state AND same block-entity tag)
     * the entry is removed.
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
     * Called every FLUSH_INTERVAL_TICKS by TemporalChangeListener.
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
