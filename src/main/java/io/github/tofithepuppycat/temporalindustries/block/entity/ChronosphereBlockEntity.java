package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChunkArea;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
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
 * Block entity for the Chronosphere: the player claims up to an 11x11 chunk area (centred on and
 * always including the home chunk), and a single jump moves every claimed chunk to the same
 * target time, paid from one shared energy pool.
 */
public class ChronosphereBlockEntity extends AbstractTimelineMachineBlockEntity {
    /** Chunks may be claimed up to this many steps from the home chunk on either axis, i.e. an 11x11 box. */
    public static final int MAX_RADIUS = 5;
    /** Home chunk plus up to this many additional chunks = 121 chunks, a full 11x11 box. */
    public static final int MAX_ADDITIONAL_CHUNKS = (MAX_RADIUS * 2 + 1) * (MAX_RADIUS * 2 + 1) - 1;

    private static final int ENERGY_CAPACITY = 500_000;
    private static final int ENERGY_TRANSFER  = 5_000;

    public enum ToggleResult { ADDED, REMOVED, IS_HOME, OUT_OF_BOUNDS, ALREADY_TRACKED_ELSEWHERE, LIMIT_REACHED, NOT_SELECTED }

    /** Claimed chunks beyond the home chunk (which is always implicitly included). */
    private final Set<Long> additionalChunks = new LinkedHashSet<>();

    public ChronosphereBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(Registration.CHRONOSPHERE_BLOCK_ENTITY.get(), blockPos, blockState, ENERGY_CAPACITY, ENERGY_TRANSFER);
    }

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

    public ChunkPos getHomeChunkPos() {
        return new ChunkPos(worldPosition);
    }

    public boolean isWithinBounds(ChunkPos pos) {
        ChunkPos home = getHomeChunkPos();
        return isWithinRadius(pos.x - home.x, pos.z - home.z);
    }

    /** The claimable area's outline: a full square box, so every cell of the map grid is claimable
     * (the Portable Chrono Marker's area-select map keeps the inscribed {@code CIRCLE} instead). */
    public static final ChunkArea.Shape CLAIM_SHAPE = ChunkArea.Shape.SQUARE;

    /** Whether a chunk offset (dx, dz) from the home chunk falls within the claimable box; shared
     * with the client screen and network handlers so shape, hit-testing, and server rule always agree. */
    public static boolean isWithinRadius(int dx, int dz) {
        return CLAIM_SHAPE.contains(MAX_RADIUS, dx, dz);
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

    // chunkPos null means the shared "All" view (the GUI's default tab); otherwise one specific
    // claimed chunk's own graph. jump() always moves every claimed chunk together regardless.
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
     * Deliberately an intersection rather than a union — unioning per-chunk commit lists would mix
     * lineages with no local-parent link, causing the graph layout to stack them all as fresh roots.
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
        // since its raw local parent is often a commit only some chunks have.
        Set<Long> sharedIds = new HashSet<>();
        for (TemporalCommit commit : sharedCommits(timeline)) sharedIds.add(commit.getId());

        Map<Long, Long> rawParents = timeline.getLocalParentsForChunk(getHomeChunkPos());
        Map<Long, Long> relinked = new HashMap<>();
        for (long id : sharedIds) {
            relinked.put(id, nearestSharedAncestor(rawParents, sharedIds, id));
        }
        return relinked;
    }

    /** Walks commitId's local-parent chain upward until it reaches a commit in sharedIds, or -1.
     * Guarded against a cycle in persisted parent links so a corrupt save can't hang the tick. */
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

        // The home chunk's head is frequently not itself shared by every claimed chunk, so the
        // "live world is here" pulse must resolve by nearest gameTime instead of walking the
        // home chunk's local-parent chain, which can dead-end on a fresh per-chunk branch marker.
        long homeHead = timeline.getChunkHeadId(getHomeChunkPos());
        TemporalCommit homeHeadCommit = timeline.getCommitById(homeHead);
        if (homeHeadCommit == null) return -1L;
        List<TemporalCommit> shared = sharedCommits(timeline);
        if (shared.isEmpty()) return -1L;
        return TemporalCommit.resolveNearest(shared, homeHeadCommit.getGameTime());
    }

    /**
     * Total cost is only priced for the currently selected node, not every node in the graph, since
     * pricing every commit would be O(H x claimedChunks x avgChunkHistory) and stall the server tick
     * on every GUI poll. Un-selected nodes simply render without a jump-cost line.
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

    /** One snapshot per claimed chunk, so the in-world ghost preview covers everything a jump would touch. */
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

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        ListTag chunkList = new ListTag();
        for (long key : additionalChunks) chunkList.add(LongTag.valueOf(key));
        tag.put("AdditionalChunks", chunkList);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        additionalChunks.clear();
        ListTag chunkList = tag.getList("AdditionalChunks", Tag.TAG_LONG);
        for (int i = 0; i < chunkList.size(); i++) {
            if (chunkList.get(i) instanceof LongTag longTag) additionalChunks.add(longTag.getAsLong());
        }
    }
}
