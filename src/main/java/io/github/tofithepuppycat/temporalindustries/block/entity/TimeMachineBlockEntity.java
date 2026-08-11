package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import io.github.tofithepuppycat.temporalindustries.menu.TimeMachineMenu;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkTimelineSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Block entity for the Time Machine: holds per-machine energy/time state and reads/writes
 * the shared {@link TemporalTimeline} for its chunk. */
@SuppressWarnings("null")
public class TimeMachineBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider, TimelineViewProvider {
    private static final int ENERGY_CAPACITY = 100_000;
    private static final int ENERGY_TRANSFER  = 1_000;
    private static final long UNSET_TIME = -1L;

    // Per-machine persistent state (the timeline data itself lives in TemporalWorldData)
    private long placedGameTime  = UNSET_TIME;
    private long selectedGameTime = UNSET_TIME;

    /** Named (rather than anonymous) so jump() can reach {@link #consumeInternal(int)} — a jump's
     * own energy cost is deducted directly, bypassing the maxExtract cap that only throttles
     * external cables/pipes pulling power out through the capability. */
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

        void consumeInternal(int amount) {
            if (amount <= 0) return;
            energy = Math.max(0, energy - amount);
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

    public TimeMachineBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(Registration.TIME_MACHINE_BLOCK_ENTITY.get(), blockPos, blockState);
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
                ChunkPos chunkPos = new ChunkPos(worldPosition);
                worldData.trackChunk(chunkPos);

                // Restore placedGameTime from the timeline if this is a reload
                if (placedGameTime == UNSET_TIME) {
                    TemporalTimeline timeline = worldData.getTimeline(level.dimension().location());
                    if (timeline != null) {
                        long earliest = timeline.getEarliestGameTimeForChunk(chunkPos);
                        if (earliest != -1L) {
                            placedGameTime  = earliest;
                            selectedGameTime = Math.max(placedGameTime, selectedGameTime == UNSET_TIME ? placedGameTime : selectedGameTime);
                            setChanged();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide && level.getServer() != null) {
            TemporalWorldData.get(level.getServer()).untrackChunk(new ChunkPos(worldPosition));
        }
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    // -------------------------------------------------------------------------
    // Tick — only handles first-placement initialisation, no block scanning

    public static void tick(Level level, BlockPos pos, BlockState state, TimeMachineBlockEntity be) {
        if (level.isClientSide) return;

        if (be.placedGameTime == UNSET_TIME) {
            be.placedGameTime  = level.getGameTime();
            be.selectedGameTime = be.placedGameTime;
            be.setChanged();
        }

        // Clamp selectedGameTime to valid range
        long now = level.getGameTime();
        if (be.selectedGameTime < be.placedGameTime || be.selectedGameTime > now) {
            be.selectedGameTime = Math.max(be.placedGameTime, Math.min(now, be.selectedGameTime));
            be.setChanged();
        }
    }

    // -------------------------------------------------------------------------
    // Timeline access (delegates to TemporalWorldData)

    /**
     * Returns all commits relevant to this machine's chunk (commits touching it, plus its own
     * branch markers), in chronological order. Used by the sync packet to send timeline data
     * to the client.
     */
    public List<TemporalCommit> getChunkCommits() {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyList();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyList();
        return timeline.getCommitsForChunk(new ChunkPos(worldPosition));
    }

    /**
     * Returns commitId -> the id it locally forked from within this machine's chunk (see
     * TemporalTimeline's class doc). Sent alongside getChunkCommits() so the client can lay out
     * the timeline graph using this chunk's real branch topology instead of guessing from order.
     */
    public Map<Long, Long> getChunkLocalParents() {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyMap();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyMap();
        return timeline.getLocalParentsForChunk(new ChunkPos(worldPosition));
    }

    /** The commit this machine's chunk's live world currently reflects. Not always the last entry
     * in getChunkCommits() — see {@link TemporalTimeline#branch}. */
    public long getChunkHeadId() {
        if (level == null || level.isClientSide || level.getServer() == null) return -1L;
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return -1L;
        return timeline.getChunkHeadId(new ChunkPos(worldPosition));
    }

    /** The ID of the commit whose gameTime is closest to selectedGameTime. */
    public long getSelectedCommitId() {
        return TemporalCommit.resolveNearest(getChunkCommits(), selectedGameTime);
    }

    /** commitId -> energy cost of jumping this machine's chunk there from its current head, for
     * every commit relevant to this chunk. Used to preview cost per node in the timeline GUI. */
    public Map<Long, Long> getChunkJumpCosts() {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyMap();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyMap();

        ChunkPos chunkPos = new ChunkPos(worldPosition);
        long headId = timeline.getChunkHeadId(chunkPos);
        Map<Long, Long> costs = new HashMap<>();
        for (TemporalCommit commit : timeline.getCommitsForChunk(chunkPos)) {
            costs.put(commit.getId(), timeline.computeJumpCost(chunkPos, commit.getGameTime(), headId, level, TimeMachineBlockEntity::costOf));
        }
        return costs;
    }

    @Override
    public List<ChunkTimelineSnapshot> getPreviewChunkSnapshots() {
        return List.of(new ChunkTimelineSnapshot(new ChunkPos(worldPosition), getChunkCommits(), getChunkLocalParents(), getChunkHeadId()));
    }

    // -------------------------------------------------------------------------
    // Jump

    /** Jumps to targetGameTime, applying it to the live world immediately. */
    public void jump(long targetGameTime) {
        if (level == null || level.isClientSide) return;

        setSelectedGameTime(targetGameTime, true);
    }

    // -------------------------------------------------------------------------
    // Rollback

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

    /** Checks out targetGameTime for this machine's chunk and applies it to the live world, paying
     * the jump's energy cost first. Does nothing (but plays a denial sound) if the machine doesn't
     * have enough energy stored. */
    public void applyTimelineView(long targetGameTime) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        TemporalTimeline timeline = worldData.getTimeline(level.dimension().location());
        if (timeline == null) return;

        ChunkPos chunkPos = new ChunkPos(worldPosition);

        // Capture the head before branch() can move it.
        long previousHead = timeline.getChunkHeadId(chunkPos);

        long cost = timeline.computeJumpCost(chunkPos, targetGameTime, previousHead, level, TimeMachineBlockEntity::costOf);
        if (cost > energyStorage.getEnergyStored()) {
            serverLevel.playSound(null, worldPosition, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0F, 1.0F);
            return;
        }
        energyStorage.consumeInternal((int) Math.min(cost, Integer.MAX_VALUE));

        timeline.branch(chunkPos, targetGameTime);
        worldData.setDirty();

        timeline.applyChunkAtTime(chunkPos, targetGameTime, previousHead, serverLevel);

        serverLevel.playSound(null, worldPosition, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static long costOf(BlockState state) {
        return ItemEnergyCosts.getCost(state.getBlock()).orElse(0);
    }

    // -------------------------------------------------------------------------
    // Accessors for menus / packets

    public long getPlacedGameTime()   { return placedGameTime; }
    public long getSelectedGameTime() { return selectedGameTime; }

    // -------------------------------------------------------------------------
    // MenuProvider

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.temporalindustries.time_machine");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory playerInventory, @NotNull Player player) {
        return new TimeMachineMenu(id, playerInventory, this,
                ContainerLevelAccess.create(Objects.requireNonNull(level), worldPosition), data);
    }

    // -------------------------------------------------------------------------
    // NBT — only energy + per-machine time values; timeline lives in TemporalWorldData

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.putLong("PlacedGameTime",   placedGameTime);
        tag.putLong("SelectedGameTime", selectedGameTime);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy"))           energyStorage.deserializeNBT(registries, tag.get("Energy"));
        if (tag.contains("PlacedGameTime"))   placedGameTime   = tag.getLong("PlacedGameTime");
        if (tag.contains("SelectedGameTime")) selectedGameTime = tag.getLong("SelectedGameTime");
    }
}
