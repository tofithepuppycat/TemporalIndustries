package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
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
 * (unlike the Time Machine, which is one energy pool per chunk).
 */
@SuppressWarnings("null")
public class ChronosphereBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider, TimelineViewProvider {
    /** Chunks may be claimed up to this many steps from the home chunk on either axis, i.e. a 5x5 box. */
    public static final int MAX_RADIUS = 2;
    /** Home chunk plus up to this many additional chunks = 25 chunks, a full 5x5 box. */
    public static final int MAX_ADDITIONAL_CHUNKS = 24;

    private static final int ENERGY_CAPACITY = 500_000;
    private static final int ENERGY_TRANSFER  = 5_000;
    private static final long UNSET_TIME = -1L;

    public enum ToggleResult { ADDED, REMOVED, IS_HOME, OUT_OF_BOUNDS, ALREADY_TRACKED_ELSEWHERE, LIMIT_REACHED, NOT_SELECTED }

    private long placedGameTime  = UNSET_TIME;
    private long selectedGameTime = UNSET_TIME;
    /** Claimed chunks beyond the home chunk (which is always implicitly included). */
    private final Set<Long> additionalChunks = new LinkedHashSet<>();
    /** Whether this Chronosphere is currently recording DELTA commits for its claimed chunks.
     * Off by default: a freshly placed/claimed Chronosphere doesn't record anything until the
     * player opts in via the GUI's auto-track tab, so idle claims don't silently accumulate history. */
    private boolean autoTrackingEnabled = false;

    /** Named (rather than anonymous) so jump()'s cost can be deducted directly, bypassing the
     * maxExtract cap that only throttles external cables/pipes pulling power out through the capability. */
    private final class MachineEnergyStorage extends EnergyStorage {
        MachineEnergyStorage() {
            super(ENERGY_CAPACITY, ENERGY_TRANSFER, ENERGY_TRANSFER);
        }

        @Override public int receiveEnergy(int max, boolean simulate) {
            int v = super.receiveEnergy(max, simulate);
            if (!simulate && v > 0) setChanged();
            return v;
        }
        @Override public int extractEnergy(int max, boolean simulate) {
            int v = super.extractEnergy(max, simulate);
            if (!simulate && v > 0) setChanged();
            return v;
        }

        void consumeInternal(long amount) {
            if (amount <= 0) return;
            energy = (int) Math.max(0, energy - amount);
            setChanged();
        }
    }

    private final MachineEnergyStorage energyStorage = new MachineEnergyStorage();

    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case 0  -> energyStorage.getEnergyStored()    & 0xFFFF;
                case 1  -> (energyStorage.getEnergyStored()   >>> 16) & 0xFFFF;
                case 2  -> energyStorage.getMaxEnergyStored() & 0xFFFF;
                case 3  -> (energyStorage.getMaxEnergyStored() >>> 16) & 0xFFFF;
                case 4  -> longPart(placedGameTime,   0);
                case 5  -> longPart(placedGameTime,   1);
                case 6  -> longPart(placedGameTime,   2);
                case 7  -> longPart(placedGameTime,   3);
                case 8  -> longPart(selectedGameTime, 0);
                case 9  -> longPart(selectedGameTime, 1);
                case 10 -> longPart(selectedGameTime, 2);
                case 11 -> longPart(selectedGameTime, 3);
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {}
        @Override public int getCount() { return 12; }
    };

    private static int longPart(long value, int part) {
        return (int) ((value >>> (part * 16)) & 0xFFFFL);
    }

    public ChronosphereBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(Registration.CHRONOSPHERE_BLOCK_ENTITY.get(), blockPos, blockState);
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
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

                if (autoTrackingEnabled) worldData.trackChunk(dimension, getHomeChunkPos(), worldPosition);
                ensureSnapshotted(worldData, timeline, serverLevel, getHomeChunkPos());
                for (long key : additionalChunks) {
                    ChunkPos chunk = new ChunkPos(key);
                    if (autoTrackingEnabled) worldData.trackChunk(dimension, chunk, worldPosition);
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

        if (be.placedGameTime == UNSET_TIME) {
            be.placedGameTime  = level.getGameTime();
            be.selectedGameTime = be.placedGameTime;
            be.setChanged();
        }

        long now = level.getGameTime();
        if (be.selectedGameTime < be.placedGameTime || be.selectedGameTime > now) {
            be.selectedGameTime = Math.max(be.placedGameTime, Math.min(now, be.selectedGameTime));
            be.setChanged();
        }

        if (level instanceof ServerLevel serverLevel && now % SNAPSHOT_CHECK_INTERVAL_TICKS == 0) {
            be.maybeResnapshot(serverLevel);
        }
    }

    /** Trades a bounded once-a-minute-per-chunk full read for keeping every other history walk
     * across all claimed chunks cheap for the rest of that minute — see ChunkSnapshot's class doc. */
    private static final int SNAPSHOT_CHECK_INTERVAL_TICKS = 1200; // 1 minute
    private static final int SNAPSHOT_COMMIT_THRESHOLD = 50;

    /** Skips chunks the world isn't currently tracking (auto-tracking off and untouched by a
     * ChronoMarker) so this timer can't manufacture a commit on its own — with auto-tracking off,
     * a chunk only accumulates history the player explicitly recorded, and this must not add to it. */
    private void maybeResnapshot(ServerLevel serverLevel) {
        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;

        TemporalWorldData worldData = TemporalWorldData.get(server);
        ResourceLocation dimension = serverLevel.dimension().location();
        TemporalTimeline timeline = worldData.getOrCreateTimeline(dimension);
        for (ChunkPos chunkPos : getAllChunks()) {
            if (!worldData.isTracked(dimension, chunkPos)) continue;
            if (timeline.getCommitsSinceSnapshot(chunkPos) < SNAPSHOT_COMMIT_THRESHOLD) continue;
            timeline.addSnapshot(serverLevel.getGameTime(), List.of(ChunkSnapshot.capture(serverLevel, chunkPos)));
            worldData.setDirty();
        }
    }

    public boolean isAutoTrackingEnabled() {
        return autoTrackingEnabled;
    }

    /** Flips auto-tracking, immediately (un)tracking every chunk this Chronosphere has claimed so
     * recording starts/stops right away rather than waiting for the next onLoad(). */
    public void setAutoTrackingEnabled(boolean enabled) {
        if (enabled == autoTrackingEnabled) return;
        autoTrackingEnabled = enabled;

        if (level instanceof ServerLevel && level.getServer() != null) {
            TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
            ResourceLocation dimension = level.dimension().location();
            for (ChunkPos chunk : getAllChunks()) {
                if (enabled) {
                    worldData.trackChunk(dimension, chunk, worldPosition);
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
     * so the map's drawn shape, click hit-testing, and the server's actual rule always agree. */
    public static boolean isWithinRadius(int dx, int dz) {
        return dx * dx + dz * dz <= MAX_RADIUS * MAX_RADIUS;
    }

    /** Every chunk this machine currently controls, home chunk first. */
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

            if (autoTrackingEnabled) worldData.trackChunk(dimension, pos, worldPosition);
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
    // Jump

    public void jump(long targetGameTime) {
        if (level == null || level.isClientSide) return;
        setSelectedGameTime(targetGameTime, true);
    }

    public void setSelectedGameTime(long targetGameTime, boolean applyToWorld) {
        if (level == null || level.isClientSide) return;

        long min = placedGameTime == UNSET_TIME ? 0L : placedGameTime;
        long max = level.getGameTime();
        long clamped = Math.max(min, Math.min(max, targetGameTime));
        if (selectedGameTime == clamped && !applyToWorld) return;

        selectedGameTime = clamped;
        if (applyToWorld) applyTimelineView(clamped);
        setChanged();
    }

    /** Read-only total cost of jumping every claimed chunk to targetGameTime from its current head. */
    public long computeTotalJumpCost(long targetGameTime) {
        if (level == null || level.getServer() == null) return 0L;
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return 0L;

        long total = 0L;
        for (ChunkPos chunk : getAllChunks()) {
            long head = timeline.getChunkHeadId(chunk);
            total += timeline.computeJumpCost(chunk, targetGameTime, head, level, ChronosphereBlockEntity::costOf);
        }
        return total;
    }

    /** Checks out targetGameTime for every claimed chunk and applies it to the live world, paying
     * the combined jump cost from the shared energy pool first. Does nothing (but plays a denial
     * sound) if there isn't enough energy stored. */
    public void applyTimelineView(long targetGameTime) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        TemporalTimeline timeline = worldData.getTimeline(level.dimension().location());
        if (timeline == null) return;

        List<ChunkPos> chunks = getAllChunks();

        // Capture every chunk's head before branch() can move any of them.
        long[] previousHeads = new long[chunks.size()];
        long totalCost = 0L;
        for (int i = 0; i < chunks.size(); i++) {
            previousHeads[i] = timeline.getChunkHeadId(chunks.get(i));
            totalCost += timeline.computeJumpCost(chunks.get(i), targetGameTime, previousHeads[i], level, ChronosphereBlockEntity::costOf);
        }

        if (totalCost > energyStorage.getEnergyStored()) {
            serverLevel.playSound(null, worldPosition, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0F, 1.0F);
            return;
        }
        energyStorage.consumeInternal(totalCost);

        for (int i = 0; i < chunks.size(); i++) {
            ChunkPos chunk = chunks.get(i);
            timeline.branch(chunk, targetGameTime);
            timeline.applyChunkAtTime(chunk, targetGameTime, previousHeads[i], serverLevel);
        }
        worldData.setDirty();

        serverLevel.playSound(null, worldPosition, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static long costOf(BlockState state) {
        return ItemEnergyCosts.getCost(state.getBlock()).orElse(0);
    }

    // -------------------------------------------------------------------------
    // TimelineViewProvider — the home chunk's own commit graph is what's displayed, but jump()
    // above still moves every claimed chunk together, and getChunkJumpCosts() below prices each
    // node by the total cost across the whole claim, not just the home chunk's share of it.

    @Override
    public List<TemporalCommit> getChunkCommits() {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyList();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyList();
        return timeline.getCommitsForChunk(getHomeChunkPos());
    }

    @Override
    public Map<Long, Long> getChunkLocalParents() {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyMap();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyMap();
        return timeline.getLocalParentsForChunk(getHomeChunkPos());
    }

    @Override
    public long getChunkHeadId() {
        if (level == null || level.isClientSide || level.getServer() == null) return -1L;
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return -1L;
        return timeline.getChunkHeadId(getHomeChunkPos());
    }

    @Override
    public long getSelectedCommitId() {
        return TemporalCommit.resolveNearest(getChunkCommits(), selectedGameTime);
    }

    /**
     * Total cost is only priced for the currently selected node, not every node in the graph.
     * computeTotalJumpCost() walks every claimed chunk's own ancestry chain, so pricing it for
     * each of the home chunk's H commits costs O(H x claimedChunks x avgChunkHistory) — with 13
     * chunks claimed and history that only ever grows over a session, that quadratic blowup was
     * enough to stall the server tick on every 20-tick GUI poll. One commit's worth of that same
     * O(claimedChunks x avgChunkHistory) work, computed only on an actual selection change, costs
     * the same as a real jump — which nobody has needed to bound. Un-selected nodes simply render
     * without a jump-cost line (see TimelineGraphWidget's tooltip).
     */
    @Override
    public Map<Long, Long> getChunkJumpCosts() {
        long selectedId = getSelectedCommitId();
        if (selectedId == -1L) return Collections.emptyMap();

        for (TemporalCommit commit : getChunkCommits()) {
            if (commit.getId() == selectedId) {
                return Map.of(selectedId, computeTotalJumpCost(commit.getGameTime()));
            }
        }
        return Collections.emptyMap();
    }

    // -------------------------------------------------------------------------
    // Multi-chunk view — see TimelineViewProvider's class doc. getChunkCommits()/etc. above stay
    // home-chunk-scoped (used by getSelectedCommitId()/getChunkJumpCosts() above, and as the
    // fallback the default interface methods delegate to); these overloads add the per-claimed-
    // chunk tabs and the "All" shared view the Chronosphere GUI shows on top of that.

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

    @Override
    public Map<Long, Long> getChunkJumpCosts(@Nullable ChunkPos chunkPos) {
        long selectedId = getSelectedCommitId(chunkPos);
        if (selectedId == -1L) return Collections.emptyMap();

        for (TemporalCommit commit : getChunkCommits(chunkPos)) {
            if (commit.getId() == selectedId) {
                return Map.of(selectedId, computeTotalJumpCost(commit.getGameTime()));
            }
        }
        return Collections.emptyMap();
    }

    /** One snapshot per claimed chunk (not just the home chunk getChunkCommits() shows), so the
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
    // Accessors for menus / packets

    @Override
    public long getPlacedGameTime()   { return placedGameTime; }
    @Override
    public long getSelectedGameTime() { return selectedGameTime; }

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
                ContainerLevelAccess.create(Objects.requireNonNull(level), worldPosition), data);
    }

    // -------------------------------------------------------------------------
    // NBT

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.putLong("PlacedGameTime",   placedGameTime);
        tag.putLong("SelectedGameTime", selectedGameTime);
        tag.putBoolean("AutoTrackingEnabled", autoTrackingEnabled);

        ListTag chunkList = new ListTag();
        for (long key : additionalChunks) chunkList.add(LongTag.valueOf(key));
        tag.put("AdditionalChunks", chunkList);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy"))           energyStorage.deserializeNBT(registries, tag.get("Energy"));
        if (tag.contains("PlacedGameTime"))   placedGameTime   = tag.getLong("PlacedGameTime");
        if (tag.contains("SelectedGameTime")) selectedGameTime = tag.getLong("SelectedGameTime");
        autoTrackingEnabled = tag.getBoolean("AutoTrackingEnabled");

        additionalChunks.clear();
        ListTag chunkList = tag.getList("AdditionalChunks", Tag.TAG_LONG);
        for (int i = 0; i < chunkList.size(); i++) {
            if (chunkList.get(i) instanceof LongTag longTag) additionalChunks.add(longTag.getAsLong());
        }
    }
}
