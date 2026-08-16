package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChunkArea;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkTimelineSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Block entity for the Chronosphere, the multi-chunk-tier time machine: the player claims up to a
 * 5x5 chunk area (centred on the block's own chunk, always included) from
 * {@link io.github.tofithepuppycat.temporalindustries.client.screen.ChronosphereScreen}'s map, and
 * a single jump moves every claimed chunk to the same target time, paid from one shared energy pool
 * (unlike the Time Machine, which is one energy pool per chunk). See
 * {@link AbstractTimelineMachineBlockEntity} for the jump/energy/re-snapshot logic shared between them.
 */
public class ChronosphereBlockEntity extends AbstractTimelineMachineBlockEntity {
    /** Chunks may be claimed up to this many steps from the home chunk on either axis, i.e. a 5x5 box. */
    public static final int MAX_RADIUS = 2;
    /** Home chunk plus up to this many additional chunks = 25 chunks, a full 5x5 box. */
    public static final int MAX_ADDITIONAL_CHUNKS = 24;

    private static final int ENERGY_CAPACITY = 500_000;
    private static final int ENERGY_TRANSFER  = 5_000;

    public enum ToggleResult { ADDED, REMOVED, IS_HOME, OUT_OF_BOUNDS, ALREADY_TRACKED_ELSEWHERE, LIMIT_REACHED, NOT_SELECTED }

    /** Claimed chunks beyond the home chunk (which is always implicitly included). */
    private final Set<Long> additionalChunks = new LinkedHashSet<>();
    /** Whether this Chronosphere is currently recording DELTA commits for its claimed chunks.
     * Off by default: a freshly placed/claimed Chronosphere doesn't record anything until the
     * player opts in via the GUI's auto-track tab, so idle claims don't silently accumulate history. */
    private boolean autoTrackingEnabled = false;

    public ChronosphereBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(Registration.CHRONOSPHERE_BLOCK_ENTITY.get(), blockPos, blockState, ENERGY_CAPACITY, ENERGY_TRANSFER);
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    @Override
    public void onLoad() {
        super.onLoad();

        if (level instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            if (server != null) {
                TemporalWorldData worldData = TemporalWorldData.get(server);
                ResourceLocation dimension = level.dimension().location();
                TemporalTimeline timeline = worldData.getOrCreateTimeline(dimension);

                if (autoTrackingEnabled) worldData.trackChunk(dimension, getHomeChunkPos(), worldPosition, serverLevel);
                ensureSnapshotted(worldData, timeline, serverLevel, getHomeChunkPos());
                for (long key : additionalChunks) {
                    ChunkPos chunk = new ChunkPos(key);
                    if (autoTrackingEnabled) worldData.trackChunk(dimension, chunk, worldPosition, serverLevel);
                    ensureSnapshotted(worldData, timeline, serverLevel, chunk);
                }
            }
        }
    }

    /** A chunk with no commits yet (freshly claimed, or claimed-but-never-touched across a
     * restart) gets a full baseline instead of waiting for its first delta — see ChunkSnapshot's
     * class doc for why ancestryChain needs one of these to exist. */
    private static void ensureSnapshotted(TemporalWorldData worldData, TemporalTimeline timeline, ServerLevel serverLevel, ChunkPos chunkPos) {
        if (!timeline.getCommitsForChunk(chunkPos).isEmpty()) return;
        timeline.addSnapshot(serverLevel.getGameTime(), List.of(ChunkSnapshot.capture(serverLevel, chunkPos)));
        worldData.setDirty();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide && level.getServer() != null) {
            TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
            ResourceLocation dimension = level.dimension().location();
            worldData.untrackChunk(dimension, getHomeChunkPos(), worldPosition);
            for (long key : additionalChunks) {
                worldData.untrackChunk(dimension, new ChunkPos(key), worldPosition);
            }
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ChronosphereBlockEntity be) {
        if (level.isClientSide) return;
        be.commonTick(level);
    }

    public boolean isAutoTrackingEnabled() {
        return autoTrackingEnabled;
    }

    /** Flips auto-tracking, immediately (un)tracking every chunk this Chronosphere has claimed so
     * recording starts/stops right away rather than waiting for the next onLoad(). */
    public void setAutoTrackingEnabled(boolean enabled) {
        if (enabled == autoTrackingEnabled) return;
        autoTrackingEnabled = enabled;

        if (level instanceof ServerLevel serverLevel && level.getServer() != null) {
            TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
            ResourceLocation dimension = level.dimension().location();
            for (ChunkPos chunk : getAllChunks()) {
                if (enabled) {
                    worldData.trackChunk(dimension, chunk, worldPosition, serverLevel);
                } else {
                    worldData.untrackChunk(dimension, chunk, worldPosition);
                }
            }
        }
        setChanged();
    }

    // -------------------------------------------------------------------------
    // Chunk selection

    public ChunkPos getHomeChunkPos() {
        return new ChunkPos(worldPosition);
    }

    public boolean isWithinBounds(ChunkPos pos) {
        ChunkPos home = getHomeChunkPos();
        return isWithinRadius(pos.x - home.x, pos.z - home.z);
    }

    /** Whether a chunk offset (dx, dz) from the home chunk falls within the claimable circle —
     * shared with {@link io.github.tofithepuppycat.temporalindustries.client.screen.ChronosphereScreen}
     * and {@link io.github.tofithepuppycat.temporalindustries.network.ChronosphereStateRequestPacket}
     * so the map's drawn shape, click hit-testing, and the server's actual rule always agree. Delegates
     * to {@link ChunkArea}, the same circular-radius math the Portable Chrono Marker's area-select map
     * and its network handlers use. */
    public static boolean isWithinRadius(int dx, int dz) {
        return ChunkArea.isWithinRadius(MAX_RADIUS, dx, dz);
    }

    /** Every chunk this machine currently controls, home chunk first. */
    @Override
    public List<ChunkPos> getAllChunks() {
        List<ChunkPos> chunks = new ArrayList<>(additionalChunks.size() + 1);
        chunks.add(getHomeChunkPos());
        for (long key : additionalChunks) chunks.add(new ChunkPos(key));
        return chunks;
    }

    public Set<Long> getAdditionalChunkKeys() {
        return additionalChunks;
    }

    public int getChunkCount() {
        return additionalChunks.size() + 1;
    }

    public ToggleResult toggleChunk(ChunkPos pos, boolean add) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return ToggleResult.OUT_OF_BOUNDS;

        ChunkPos home = getHomeChunkPos();
        if (pos.equals(home)) return ToggleResult.IS_HOME;
        if (!isWithinBounds(pos)) return ToggleResult.OUT_OF_BOUNDS;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        long key = pos.toLong();

        if (add) {
            if (additionalChunks.contains(key)) return ToggleResult.ADDED;
            if (additionalChunks.size() >= MAX_ADDITIONAL_CHUNKS) return ToggleResult.LIMIT_REACHED;
            if (worldData.isTracked(dimension, pos)) return ToggleResult.ALREADY_TRACKED_ELSEWHERE;

            if (autoTrackingEnabled) worldData.trackChunk(dimension, pos, worldPosition, serverLevel);
            additionalChunks.add(key);
            ensureSnapshotted(worldData, worldData.getOrCreateTimeline(dimension), serverLevel, pos);
            setChanged();
            return ToggleResult.ADDED;
        } else {
            if (!additionalChunks.remove(key)) return ToggleResult.NOT_SELECTED;
            worldData.untrackChunk(dimension, pos, worldPosition);
            setChanged();
            return ToggleResult.REMOVED;
        }
    }

    // -------------------------------------------------------------------------
    // Jump — computeTotalJumpCost/applyTimelineView/jump/setSelectedGameTime all live in
    // AbstractTimelineMachineBlockEntity, operating over getAllChunks() above.

    /** Wipes every claimed chunk's recorded history and re-baselines each from its current live
     * state, without changing a single block — the world stays exactly as it is, there's just
     * nothing left to jump back to until new history accumulates from here. Also resets
     * placed/selected game time to now, same as if the Chronosphere had just been placed. */
    public void deleteAllHistory() {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        TemporalTimeline timeline = worldData.getOrCreateTimeline(level.dimension().location());

        for (ChunkPos chunk : getAllChunks()) {
            timeline.clearChunkHistory(chunk);
            ensureSnapshotted(worldData, timeline, serverLevel, chunk);
        }

        placedGameTime = level.getGameTime();
        selectedGameTime = placedGameTime;
        worldData.setDirty();
        setChanged();
    }

    // -------------------------------------------------------------------------
    // TimelineViewProvider — chunkPos null means the shared "All" view (the GUI's default tab);
    // otherwise one specific claimed chunk's own graph (home chunk included). jump() always moves
    // every claimed chunk together regardless of which one is being displayed.

    @Override
    public List<ChunkPos> getViewableChunks() {
        return getAllChunks();
    }

    @Override
    public List<TemporalCommit> getChunkCommits(@Nullable ChunkPos chunkPos) {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyList();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyList();
        if (chunkPos != null) return timeline.getCommitsForChunk(chunkPos);
        return sharedCommits(timeline);
    }

    /**
     * The shared "All" view: only commits relevant to EVERY claimed chunk, in creation order.
     *
     * <p>Deliberately an intersection rather than a union. Each chunk keeps its own independent
     * ancestry (see {@link TemporalTimeline}'s class doc), so unioning the claimed chunks' commit
     * lists produces commits from lineages that have no local-parent link to each other — the
     * graph layout then treats every one of them as a fresh root, stacking them all at row 0 with
     * their own "… Timeline" labels drawn on top of each other. The commits every chunk shares are
     * exactly the ones that describe the claim as a whole (a Chronosphere's jumps batch every
     * claimed chunk into the same DELTA/SNAPSHOT commits), and they form a single connected chain
     * that lays out cleanly. Per-chunk detail is still one tab click away.
     */
    private List<TemporalCommit> sharedCommits(TemporalTimeline timeline) {
        List<ChunkPos> chunks = getAllChunks();
        List<TemporalCommit> shared = new ArrayList<>(timeline.getCommitsForChunk(chunks.get(0)));
        for (int i = 1; i < chunks.size() && !shared.isEmpty(); i++) {
            Set<Long> idsInChunk = new HashSet<>();
            for (TemporalCommit commit : timeline.getCommitsForChunk(chunks.get(i))) {
                idsInChunk.add(commit.getId());
            }
            shared.removeIf(commit -> !idsInChunk.contains(commit.getId()));
        }
        shared.sort(Comparator.comparingLong(TemporalCommit::getId));
        return shared;
    }

    @Override
    public Map<Long, Long> getChunkLocalParents(@Nullable ChunkPos chunkPos) {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyMap();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyMap();
        if (chunkPos != null) return timeline.getLocalParentsForChunk(chunkPos);

        // Shared view: re-link each shared commit to its nearest ancestor that is ALSO shared,
        // rather than passing through the raw per-chunk links. A shared commit's immediate local
        // parent is often a commit only some chunks have (so it isn't drawn here); left as-is that
        // link would dangle, the layout would treat the commit as a root, and the shared chain
        // would fragment back into the overlapping pile this view exists to avoid.
        Set<Long> sharedIds = new HashSet<>();
        for (TemporalCommit commit : sharedCommits(timeline)) sharedIds.add(commit.getId());

        Map<Long, Long> rawParents = timeline.getLocalParentsForChunk(getHomeChunkPos());
        Map<Long, Long> relinked = new HashMap<>();
        for (long id : sharedIds) {
            relinked.put(id, nearestSharedAncestor(rawParents, sharedIds, id));
        }
        return relinked;
    }

    /** Walks commitId's local-parent chain upward until it reaches a commit in sharedIds, or -1 if
     * it runs out. Guarded against a cycle in the (persisted) parent links so a corrupt save can't
     * hang the server tick here. */
    private static long nearestSharedAncestor(Map<Long, Long> rawParents, Set<Long> sharedIds, long commitId) {
        Set<Long> visited = new HashSet<>();
        long current = rawParents.getOrDefault(commitId, -1L);
        while (current >= 0 && visited.add(current)) {
            if (sharedIds.contains(current)) return current;
            current = rawParents.getOrDefault(current, -1L);
        }
        return -1L;
    }

    @Override
    public long getChunkHeadId(@Nullable ChunkPos chunkPos) {
        if (level == null || level.isClientSide || level.getServer() == null) return -1L;
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return -1L;
        if (chunkPos != null) return timeline.getChunkHeadId(chunkPos);

        // The home chunk's actual head is frequently NOT itself shared by every claimed chunk (a
        // delta/branch commit only touches the chunks it actually affects), and a head id the
        // shared view never draws would leave its "live world is here" pulse invisible. Resolve by
        // TIME rather than walking the home chunk's own local-parent chain for a shared ancestor —
        // after a jump lands the home chunk on a fresh per-chunk branch marker, that marker has no
        // shared ancestor of its own to walk to (each claimed chunk gets its own distinct marker, so
        // the walk can run out and strand the pulse on whatever shared commit was drawn before the
        // jump). Nearest-by-gameTime always finds the shared node that best represents where the
        // live world actually is now, since a jump target's marker always carries the exact gameTime
        // it was checked out to.
        long homeHead = timeline.getChunkHeadId(getHomeChunkPos());
        TemporalCommit homeHeadCommit = timeline.getCommitById(homeHead);
        if (homeHeadCommit == null) return -1L;
        List<TemporalCommit> shared = sharedCommits(timeline);
        if (shared.isEmpty()) return -1L;
        return TemporalCommit.resolveNearest(shared, homeHeadCommit.getGameTime());
    }

    /**
     * Total cost is only priced for the currently selected node, not every node in the graph.
     * computeTotalJumpCost() walks every claimed chunk's own ancestry chain, so pricing it for
     * each of H commits costs O(H x claimedChunks x avgChunkHistory) — with 13 chunks claimed and
     * history that only ever grows over a session, that quadratic blowup was enough to stall the
     * server tick on every 20-tick GUI poll. One commit's worth of that same
     * O(claimedChunks x avgChunkHistory) work, computed only on an actual selection change, costs
     * the same as a real jump — which nobody has needed to bound. Un-selected nodes simply render
     * without a jump-cost line (see TimelineGraphWidget's tooltip).
     */
    @Override
    public Map<Long, Long> getChunkJumpCosts(@Nullable ChunkPos chunkPos) {
        long selectedId = getSelectedCommitId(chunkPos);
        if (selectedId == -1L) return Collections.emptyMap();

        for (TemporalCommit commit : getChunkCommits(chunkPos)) {
            if (commit.getId() == selectedId) {
                return Map.of(selectedId, computeTotalJumpCost(commit.getGameTime(), selectedId));
            }
        }
        return Collections.emptyMap();
    }

    /** One snapshot per claimed chunk (not just whichever one tab is currently showing), so the
     * in-world ghost preview covers everything a jump would actually touch. */
    @Override
    public List<ChunkTimelineSnapshot> getPreviewChunkSnapshots() {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyList();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyList();

        List<ChunkTimelineSnapshot> snapshots = new ArrayList<>();
        for (ChunkPos chunk : getAllChunks()) {
            snapshots.add(new ChunkTimelineSnapshot(chunk,
                    timeline.getCommitsForChunk(chunk),
                    timeline.getLocalParentsForChunk(chunk),
                    timeline.getChunkHeadId(chunk)));
        }
        return snapshots;
    }

    // -------------------------------------------------------------------------
    // MenuProvider

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.temporalindustries.chronosphere");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory playerInventory, @NotNull Player player) {
        return new ChronosphereMenu(id, playerInventory, this,
                ContainerLevelAccess.create(Objects.requireNonNull(level), worldPosition), getContainerData());
    }

    // -------------------------------------------------------------------------
    // NBT

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("AutoTrackingEnabled", autoTrackingEnabled);

        ListTag chunkList = new ListTag();
        for (long key : additionalChunks) chunkList.add(LongTag.valueOf(key));
        tag.put("AdditionalChunks", chunkList);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        autoTrackingEnabled = tag.getBoolean("AutoTrackingEnabled");

        additionalChunks.clear();
        ListTag chunkList = tag.getList("AdditionalChunks", Tag.TAG_LONG);
        for (int i = 0; i < chunkList.size(); i++) {
            if (chunkList.get(i) instanceof LongTag longTag) additionalChunks.add(longTag.getAsLong());
        }
    }
}
