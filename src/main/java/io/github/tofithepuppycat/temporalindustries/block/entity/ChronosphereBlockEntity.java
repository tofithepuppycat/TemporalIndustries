package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
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
import java.util.HashMap;
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

        if (level != null && !level.isClientSide) {
            MinecraftServer server = level.getServer();
            if (server != null) {
                TemporalWorldData worldData = TemporalWorldData.get(server);
                worldData.trackChunk(getHomeChunkPos());
                for (long key : additionalChunks) {
                    worldData.trackChunk(new ChunkPos(key));
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide && level.getServer() != null) {
            TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
            worldData.untrackChunk(getHomeChunkPos());
            for (long key : additionalChunks) {
                worldData.untrackChunk(new ChunkPos(key));
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
        if (level == null || level.isClientSide || level.getServer() == null) return ToggleResult.OUT_OF_BOUNDS;

        ChunkPos home = getHomeChunkPos();
        if (pos.equals(home)) return ToggleResult.IS_HOME;
        if (!isWithinBounds(pos)) return ToggleResult.OUT_OF_BOUNDS;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        long key = pos.toLong();

        if (add) {
            if (additionalChunks.contains(key)) return ToggleResult.ADDED;
            if (additionalChunks.size() >= MAX_ADDITIONAL_CHUNKS) return ToggleResult.LIMIT_REACHED;
            if (worldData.isTracked(pos)) return ToggleResult.ALREADY_TRACKED_ELSEWHERE;

            worldData.trackChunk(pos);
            additionalChunks.add(key);
            setChanged();
            return ToggleResult.ADDED;
        } else {
            if (!additionalChunks.remove(key)) return ToggleResult.NOT_SELECTED;
            worldData.untrackChunk(pos);
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

        additionalChunks.clear();
        ListTag chunkList = tag.getList("AdditionalChunks", Tag.TAG_LONG);
        for (int i = 0; i < chunkList.size(); i++) {
            if (chunkList.get(i) instanceof LongTag longTag) additionalChunks.add(longTag.getAsLong());
        }
    }
}
