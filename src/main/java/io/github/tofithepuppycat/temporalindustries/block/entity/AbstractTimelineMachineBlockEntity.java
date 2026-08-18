package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Predicate;

/**
 * Shared logic between {@link TimeMachineBlockEntity} (one tracked chunk) and
 * {@link ChronosphereBlockEntity} (up to 25, sharing one energy pool) — the two differ only in how
 * many chunks a jump touches ({@link #getAllChunks()}) and in their energy pool size; everything
 * else (energy storage plumbing, the placed/selected game time clamp, periodic re-snapshotting,
 * jump/checkout, and the NBT for all of the above) was previously duplicated line-for-line across
 * both block entities, so every fix to one had to be repeated by hand in the other.
 */
@SuppressWarnings("null")
public abstract class AbstractTimelineMachineBlockEntity extends BlockEntity
        implements net.minecraft.world.MenuProvider, TimelineViewProvider {
    protected static final long UNSET_TIME = -1L;

    /** Entropy is a balance between ORD (0, order) and CHS ({@link #ENTROPY_MAX}, chaos), starting
     * centered. Per IDEAS.md, a machine drifts toward chaos while its selected view sits away from
     * the present and settles back toward balance once it's caught up — the bar this backs is
     * purely observational for now; nothing yet gates a jump on it. */
    public static final int ENTROPY_MAX = 1000;
    private static final int ENTROPY_BALANCED = ENTROPY_MAX / 2;
    private static final int ENTROPY_DRIFT_INTERVAL_TICKS = 20; // 1 second

    protected long placedGameTime = UNSET_TIME;
    protected long selectedGameTime = UNSET_TIME;
    protected int entropy = ENTROPY_BALANCED;

    /** Named (rather than anonymous) so a jump's own cost can be deducted directly, bypassing the
     * maxExtract cap that only throttles external cables/pipes pulling power out through the capability. */
    private final class MachineEnergyStorage extends EnergyStorage {
        MachineEnergyStorage(int capacity, int transfer) {
            super(capacity, transfer, transfer);
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

    private final MachineEnergyStorage energyStorage;
    private final ContainerData data;

    protected AbstractTimelineMachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                                  int energyCapacity, int energyTransfer) {
        super(type, pos, state);
        this.energyStorage = new MachineEnergyStorage(energyCapacity, energyTransfer);
        this.data = new ContainerData() {
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
                    case 12 -> entropy;
                    default -> 0;
                };
            }
            @Override public void set(int index, int value) {}
            @Override public int getCount() { return 13; }
        };
    }

    private static int longPart(long value, int part) {
        return (int) ((value >>> (part * 16)) & 0xFFFFL);
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public ContainerData getContainerData() {
        return data;
    }

    public int getEntropy() { return entropy; }

    /** Every chunk a jump on this machine moves together — one for a Time Machine, up to 25 for a
     * Chronosphere. Home/primary chunk first. */
    public abstract List<ChunkPos> getAllChunks();

    // -------------------------------------------------------------------------
    // Tick — normally just first-placement initialisation, no block scanning; the one deliberate
    // exception is the rare re-snapshot check below, which trades a bounded once-a-minute full
    // chunk read (per claimed chunk) for keeping every other history walk cheap for the rest of
    // that minute.

    private static final int SNAPSHOT_CHECK_INTERVAL_TICKS = 1200; // 1 minute

    /** Clamps placed/selected game time and, once a minute, re-snapshots any claimed chunk whose
     * history has drifted far enough from its last baseline — called from each subclass's static
     * {@code tick} entrypoint (kept per-subclass only because BlockEntityTicker needs a concrete
     * type to bind to at registration). */
    protected final void commonTick(Level level) {
        if (placedGameTime == UNSET_TIME) {
            placedGameTime = level.getGameTime();
            selectedGameTime = placedGameTime;
            setChanged();
        }

        long now = level.getGameTime();
        if (selectedGameTime < placedGameTime || selectedGameTime > now) {
            selectedGameTime = Math.max(placedGameTime, Math.min(now, selectedGameTime));
            setChanged();
        }

        if (level instanceof ServerLevel serverLevel && now % SNAPSHOT_CHECK_INTERVAL_TICKS == 0) {
            maybeResnapshot(serverLevel);
        }

        if (now % ENTROPY_DRIFT_INTERVAL_TICKS == 0) {
            driftEntropy(now);
        }
    }

    /** Ticks entropy one step toward chaos while the selected view is behind the present, or one
     * step back toward balance once it's caught up — see {@link #entropy}'s javadoc. */
    private void driftEntropy(long now) {
        int target = selectedGameTime < now ? ENTROPY_MAX : ENTROPY_BALANCED;
        if (entropy == target) return;
        entropy += Integer.signum(target - entropy);
        setChanged();
    }

    /** Skips chunks the world isn't currently tracking (auto-tracking off, for a Chronosphere; or
     * a Time Machine that hasn't finished its first onLoad() yet) so this timer can't manufacture a
     * commit on its own — untracked chunks must only accumulate history the player explicitly
     * recorded. */
    private void maybeResnapshot(ServerLevel serverLevel) {
        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;

        TemporalWorldData worldData = TemporalWorldData.get(server);
        ResourceLocation dimension = serverLevel.dimension().location();
        TemporalTimeline timeline = worldData.getOrCreateTimeline(dimension);
        for (ChunkPos chunkPos : getAllChunks()) {
            if (!worldData.isTracked(dimension, chunkPos)) continue;
            if (timeline.getCommitsSinceSnapshot(chunkPos) < TemporalTimeline.SNAPSHOT_COMMIT_THRESHOLD) continue;
            timeline.addSnapshot(serverLevel.getGameTime(), List.of(ChunkSnapshot.capture(serverLevel, chunkPos)));
            worldData.setDirty();
        }
    }

    // -------------------------------------------------------------------------
    // Jump / rollback — shared by every TimelineViewProvider implementation, since a Time Machine
    // is just a Chronosphere whose getAllChunks() always returns a single chunk.

    @Override
    public JumpResult jump(long targetGameTime, long targetCommitId) {
        if (level == null || level.isClientSide) return JumpResult.SUCCESS;
        return setSelectedGameTime(targetGameTime, targetCommitId, true);
    }

    @Override
    public void setSelectedGameTime(long targetGameTime, boolean applyToWorld) {
        setSelectedGameTime(targetGameTime, TemporalCommit.NO_PREFERRED_COMMIT, applyToWorld);
    }

    /** Same as {@link #setSelectedGameTime(long, boolean)}, but see {@link #jump(long, long)} for
     * what targetCommitId is for. */
    public JumpResult setSelectedGameTime(long targetGameTime, long targetCommitId, boolean applyToWorld) {
        if (level == null || level.isClientSide) return JumpResult.SUCCESS;

        long min = placedGameTime == UNSET_TIME ? 0L : placedGameTime;
        long max = level.getGameTime();
        long clamped = Math.max(min, Math.min(max, targetGameTime));
        if (selectedGameTime == clamped && !applyToWorld) return JumpResult.SUCCESS;

        selectedGameTime = clamped;
        JumpResult result = applyToWorld ? applyTimelineView(clamped, targetCommitId) : JumpResult.SUCCESS;
        setChanged();
        return result;
    }

    /** Read-only total cost of jumping every chunk getAllChunks() returns to targetGameTime from
     * its current head. */
    public long computeTotalJumpCost(long targetGameTime, long targetCommitId) {
        if (level == null || level.getServer() == null) return 0L;
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        TemporalTimeline timeline = worldData.getTimeline(dimension);
        if (timeline == null) return 0L;

        Predicate<BlockPos> isGlued = pos -> worldData.isGlued(dimension, pos);
        long total = 0L;
        for (ChunkPos chunk : getAllChunks()) {
            long head = timeline.getChunkHeadId(chunk);
            total += timeline.computeJumpCost(chunk, targetGameTime, head, level, AbstractTimelineMachineBlockEntity::costOf, targetCommitId, isGlued);
        }
        return total;
    }

    /** Checks out targetGameTime for every chunk getAllChunks() returns and applies it to the live
     * world, paying the combined jump cost from the energy pool first. Does nothing but return
     * INSUFFICIENT_ENERGY (and play a denial sound) if there isn't enough energy stored — callers
     * with a player to notify (see RollbackChunkPacket) turn that into a chat message. */
    public JumpResult applyTimelineView(long targetGameTime, long targetCommitId) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return JumpResult.SUCCESS;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        TemporalTimeline timeline = worldData.getTimeline(dimension);
        if (timeline == null) return JumpResult.SUCCESS;

        // Block changes since the last periodic flush (TemporalChangeListener, every 20 ticks) sit
        // uncommitted in TemporalWorldData's pending-delta buffer, invisible to the commit graph a
        // rollback walks. Without flushing first, a block placed moments ago could be skipped
        // entirely by the jump below instead of being undone.
        worldData.flushPendingDeltas(level.getGameTime());

        List<ChunkPos> chunks = getAllChunks();
        Predicate<BlockPos> isGlued = pos -> worldData.isGlued(dimension, pos);

        // Capture every chunk's head before branch() can move any of them.
        long[] previousHeads = new long[chunks.size()];
        long totalCost = 0L;
        for (int i = 0; i < chunks.size(); i++) {
            previousHeads[i] = timeline.getChunkHeadId(chunks.get(i));
            totalCost += timeline.computeJumpCost(chunks.get(i), targetGameTime, previousHeads[i], level, AbstractTimelineMachineBlockEntity::costOf, targetCommitId, isGlued);
        }

        if (totalCost > energyStorage.getEnergyStored()) {
            serverLevel.playSound(null, worldPosition, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0F, 1.0F);
            return JumpResult.INSUFFICIENT_ENERGY;
        }
        energyStorage.consumeInternal(totalCost);

        for (int i = 0; i < chunks.size(); i++) {
            ChunkPos chunk = chunks.get(i);
            timeline.branch(chunk, targetGameTime, targetCommitId);
            timeline.applyChunkAtTime(chunk, targetGameTime, previousHeads[i], serverLevel, targetCommitId, isGlued);
        }
        worldData.setDirty();

        serverLevel.playSound(null, worldPosition, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
        return JumpResult.SUCCESS;
    }

    protected static long costOf(BlockState state) {
        return ItemEnergyCosts.getCost(state.getBlock()).orElse(0);
    }

    // -------------------------------------------------------------------------
    // Accessors for menus / packets

    @Override
    public long getPlacedGameTime()   { return placedGameTime; }
    @Override
    public long getSelectedGameTime() { return selectedGameTime; }

    // -------------------------------------------------------------------------
    // NBT — only energy + per-machine time values; the timeline itself lives in TemporalWorldData.
    // Subclasses call super then add their own fields (Chronosphere: auto-tracking + claimed chunks).

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.putLong("PlacedGameTime",   placedGameTime);
        tag.putLong("SelectedGameTime", selectedGameTime);
        tag.putInt("Entropy", entropy);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy"))           energyStorage.deserializeNBT(registries, tag.get("Energy"));
        if (tag.contains("PlacedGameTime"))   placedGameTime   = tag.getLong("PlacedGameTime");
        if (tag.contains("SelectedGameTime")) selectedGameTime = tag.getLong("SelectedGameTime");
        if (tag.contains("Entropy"))          entropy          = tag.getInt("Entropy");
    }
}
