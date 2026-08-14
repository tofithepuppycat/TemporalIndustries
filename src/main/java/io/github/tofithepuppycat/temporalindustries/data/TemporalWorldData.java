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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
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
     * radius) never grows past one claim, so a single matching untrackChunk always fully releases it. */
    public void trackChunk(ResourceLocation dimension, ChunkPos pos, Object owner) {
        trackedChunkOwners.computeIfAbsent(dimension, d -> new HashMap<>())
                .computeIfAbsent(pos.toLong(), c -> new HashSet<>())
                .add(owner);
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
     * If the net effect is no change (prev == new) the entry is removed.
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
            if (merged.getPreviousState().equals(merged.getNewState())) {
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

        return tag;
    }
}
