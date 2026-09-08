package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Shared logic between {@link ChronovaultBlockEntity} (one tracked chunk) and
 * {@link ChronosphereBlockEntity} (up to 25, sharing one energy pool). Subclasses only differ in
 * how many chunks a jump touches ({@link #getAllChunks()}) and energy pool size; everything else
 * (energy storage, game time clamping, re-snapshotting, jump/checkout, NBT) lives here.
 */
@SuppressWarnings("null")
public abstract class AbstractTimelineMachineBlockEntity extends BlockEntity
        implements net.minecraft.world.MenuProvider, TimelineViewProvider, EntropyInfoProvider {
    protected static final long UNSET_TIME = -1L;

    /** Entropy is a balance between order (0) and chaos ({@link #ENTROPY_MAX}), backed by two
     * FluidTanks whose contents always sum to ENTROPY_MAX. It drifts toward chaos while the
     * selected view sits away from the present, raising jump cost and autotrack drain; a jump
     * into the past adds chaos, into the future adds order. */
    public static final int ENTROPY_MAX = 10000;
    private static final int ENTROPY_BALANCED = ENTROPY_MAX / 2;
    private static final int ENTROPY_DRIFT_INTERVAL_TICKS = 20; // 1 second
    private static final int ENTROPY_DRIFT_STEP = 10; // per interval tick
    private static final int JUMP_ENTROPY_SHIFT = 250; // flat shift per successful jump
    private static final double ENTROPY_COST_SCALE = 1.0; // jump-cost multiplier at max distance from balance
    private static final int AUTOTRACK_BASE_FE_PER_TICK = 1;
    private static final double AUTOTRACK_ENTROPY_SCALE = 4.0; // additional FE/tick at max distance from balance

    protected long placedGameTime = UNSET_TIME;
    protected long selectedGameTime = UNSET_TIME;
    /** Whether this machine is recording DELTA commits for getAllChunks(). Defaults off for a
     * Chronosphere, on for a Chronovault. */
    protected boolean autoTrackingEnabled = false;

    private final class MachineFluidHandler implements IFluidHandler {
        @Override public int getTanks() { return 2; }

        @Override public @NotNull FluidStack getFluidInTank(int tank) {
            return tank == 0 ? orderTank.getFluid() : chaosTank.getFluid();
        }

        @Override public int getTankCapacity(int tank) {
            return tank == 0 ? orderTank.getCapacity() : chaosTank.getCapacity();
        }

        @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return tank == 0 ? orderTank.isFluidValid(stack) : chaosTank.isFluidValid(stack);
        }

        /** Filling one tank displaces the same amount from the other, keeping orderTank+chaosTank fixed at ENTROPY_MAX. */
        @Override public int fill(FluidStack resource, FluidAction action) {
            if (orderTank.isFluidValid(resource)) {
                int accepted = Math.min(resource.getAmount(), chaosTank.getFluidAmount());
                int filled = orderTank.fill(new FluidStack(resource.getFluid(), accepted), action);
                if (filled > 0 && action.execute()) chaosTank.drain(filled, FluidAction.EXECUTE);
                return filled;
            }
            if (chaosTank.isFluidValid(resource)) {
                int accepted = Math.min(resource.getAmount(), orderTank.getFluidAmount());
                int filled = chaosTank.fill(new FluidStack(resource.getFluid(), accepted), action);
                if (filled > 0 && action.execute()) orderTank.drain(filled, FluidAction.EXECUTE);
                return filled;
            }
            return 0;
        }

        /** Draining one tank grows the other by the same amount, same fixed-sum invariant as {@link #fill}. */
        @Override public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (!orderTank.getFluid().isEmpty() && orderTank.getFluid().getFluid().isSame(resource.getFluid())) {
                return drainOrder(resource.getAmount(), action);
            }
            if (!chaosTank.getFluid().isEmpty() && chaosTank.getFluid().getFluid().isSame(resource.getFluid())) {
                return drainChaos(resource.getAmount(), action);
            }
            return FluidStack.EMPTY;
        }

        @Override public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            if (!orderTank.getFluid().isEmpty()) return drainOrder(maxDrain, action);
            if (!chaosTank.getFluid().isEmpty()) return drainChaos(maxDrain, action);
            return FluidStack.EMPTY;
        }

        private FluidStack drainOrder(int amount, FluidAction action) {
            FluidStack drained = orderTank.drain(amount, action);
            if (!drained.isEmpty() && action.execute()) {
                chaosTank.fill(new FluidStack(Registration.CHAOS_FLUID.get(), drained.getAmount()), FluidAction.EXECUTE);
            }
            return drained;
        }

        private FluidStack drainChaos(int amount, FluidAction action) {
            FluidStack drained = chaosTank.drain(amount, action);
            if (!drained.isEmpty() && action.execute()) {
                orderTank.fill(new FluidStack(Registration.ORDER_FLUID.get(), drained.getAmount()), FluidAction.EXECUTE);
            }
            return drained;
        }
    }

    private final FluidTank orderTank = new FluidTank(ENTROPY_MAX) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.ORDER_FLUID.get());
        }
    };
    private final FluidTank chaosTank = new FluidTank(ENTROPY_MAX) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.CHAOS_FLUID.get());
        }
    };
    private final MachineFluidHandler fluidHandler = new MachineFluidHandler();

    /** Named so a jump's own cost can be deducted directly, bypassing the maxExtract cap that only throttles external capability access. */
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
        orderTank.setFluid(new FluidStack(Registration.ORDER_FLUID.get(), ENTROPY_BALANCED));
        chaosTank.setFluid(new FluidStack(Registration.CHAOS_FLUID.get(), ENTROPY_BALANCED));
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
                    case 12 -> getEntropy();
                    case 13 -> orderTank.getFluidAmount();
                    case 14 -> chaosTank.getFluidAmount();
                    case 15 -> ENTROPY_MAX;
                    default -> 0;
                };
            }
            @Override public void set(int index, int value) {}
            @Override public int getCount() { return 16; }
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

    /** Order/chaos balance scalar: 0 is pure order, {@link #ENTROPY_MAX} pure chaos. */
    public int getEntropy() { return chaosTank.getFluidAmount(); }

    public IFluidHandler getFluidHandler() { return fluidHandler; }
    public FluidTank getOrderTank() { return orderTank; }
    public FluidTank getChaosTank() { return chaosTank; }

    @Override
    public List<Component> getEntropyTooltip() {
        return List.of(getDisplayName().copy().withStyle(ChatFormatting.WHITE));
    }

    @Override public boolean hasEntropyBalance() { return true; }
    @Override public int getEntropyBalance() { return getEntropy(); }
    @Override public int getEntropyBalanceMax() { return ENTROPY_MAX; }

    /** Mirrors {@link #driftEntropy}'s direction so the client can recompute the target without ticking. */
    @Override
    public float getEntropyRatePerSecond() {
        if (level == null) return 0f;
        int entropy = getEntropy();
        int target = selectedGameTime < level.getGameTime() ? ENTROPY_MAX : ENTROPY_BALANCED;
        if (entropy == target) return 0f;
        return Integer.signum(target - entropy) * ENTROPY_DRIFT_STEP / 100f;
    }

    /** Transfers up to |delta| units from order to chaos (delta > 0) or vice versa, clamped to [0, ENTROPY_MAX]. */
    private void shiftEntropyToward(int delta) {
        if (delta == 0) return;
        int current = getEntropy();
        int clamped = Math.max(0, Math.min(ENTROPY_MAX, current + delta)) - current;
        if (clamped == 0) return;
        if (clamped > 0) {
            chaosTank.fill(new FluidStack(Registration.CHAOS_FLUID.get(), clamped), IFluidHandler.FluidAction.EXECUTE);
            orderTank.drain(clamped, IFluidHandler.FluidAction.EXECUTE);
        } else {
            orderTank.fill(new FluidStack(Registration.ORDER_FLUID.get(), -clamped), IFluidHandler.FluidAction.EXECUTE);
            chaosTank.drain(-clamped, IFluidHandler.FluidAction.EXECUTE);
        }
        setChanged();
        syncToClients();
    }

    /** Jump FE cost multiplier: 1x when balanced, rising to 1+{@link #ENTROPY_COST_SCALE} at either extreme. */
    private double jumpCostMultiplier() {
        double distance = Math.abs(getEntropy() - ENTROPY_BALANCED) / (double) ENTROPY_BALANCED;
        return 1.0 + ENTROPY_COST_SCALE * distance;
    }

    /** Every chunk a jump on this machine moves together, home/primary chunk first. */
    public abstract List<ChunkPos> getAllChunks();

    public boolean isAutoTrackingEnabled() {
        return autoTrackingEnabled;
    }

    /** Flips auto-tracking, immediately (un)tracking every chunk getAllChunks() returns. */
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
        syncToClients();
    }

    /** A chunk with no commits yet gets a full baseline instead of waiting for its first delta. */
    protected static void ensureSnapshotted(TemporalWorldData worldData, TemporalTimeline timeline, ServerLevel serverLevel, ChunkPos chunkPos) {
        if (!timeline.getCommitsForChunk(chunkPos).isEmpty()) return;
        timeline.addSnapshot(serverLevel.getGameTime(), List.of(ChunkSnapshot.capture(serverLevel, chunkPos)));
        worldData.setDirty();
    }

    /** Wipes recorded history for every chunk getAllChunks() returns and re-baselines from current
     * live state (no blocks change). Also resets placed/selected game time to now. */
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
        syncToClients();
    }

    /** Deletes branchCommitId and everything forked from it, scoped to this machine's home chunk
     * (its only chunk for a Chronovault; a Chronosphere's shared graph can only ever show a BRANCH
     * commit belonging to its home chunk, since a branch marker is exclusive to the one chunk it
     * was checked out for). No-op if the branch's chunk head sits inside it. */
    @Override
    public boolean deleteBranch(long branchCommitId) {
        if (!(level instanceof ServerLevel) || level.getServer() == null) return false;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        TemporalTimeline timeline = worldData.getTimeline(level.dimension().location());
        if (timeline == null) return false;

        boolean deleted = timeline.deleteBranch(getAllChunks().get(0), branchCommitId);
        if (deleted) {
            worldData.setDirty();
            setChanged();
            syncToClients();
        }
        return deleted;
    }

    private static final int SNAPSHOT_CHECK_INTERVAL_TICKS = 1200; // 1 minute

    /** Clamps placed/selected game time and, once a minute, re-snapshots any claimed chunk whose
     * history has drifted far enough from its last baseline. Called from each subclass's static tick entrypoint. */
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

        if (autoTrackingEnabled) {
            drainAutoTrackingEnergy();
        }
    }

    /** Ticks entropy one step toward chaos while the selected view is behind the present, or back toward balance once caught up. */
    private void driftEntropy(long now) {
        int target = selectedGameTime < now ? ENTROPY_MAX : ENTROPY_BALANCED;
        int entropy = getEntropy();
        if (entropy == target) return;
        int step = Integer.signum(target - entropy) * ENTROPY_DRIFT_STEP;
        if (Math.abs(step) > Math.abs(target - entropy)) step = target - entropy;
        shiftEntropyToward(step);
    }

    /** Per-tick FE drain while auto-tracking is on, scaled by distance from entropy balance. Skips
     * (rather than disabling tracking) when energy is insufficient, so history keeps recording. */
    private void drainAutoTrackingEnergy() {
        double distance = Math.abs(getEntropy() - ENTROPY_BALANCED) / (double) ENTROPY_BALANCED;
        int feThisTick = (int) Math.round(AUTOTRACK_BASE_FE_PER_TICK + AUTOTRACK_ENTROPY_SCALE * distance);
        energyStorage.consumeInternal(Math.min(feThisTick, energyStorage.getEnergyStored()));
    }

    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Skips chunks the world isn't currently tracking, so this timer can't manufacture a commit
     * on its own; untracked chunks only accumulate history the player explicitly recorded. */
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

    @Override
    public JumpResult jump(long targetGameTime, long targetCommitId) {
        if (level == null || level.isClientSide) return JumpResult.SUCCESS;
        return setSelectedGameTime(targetGameTime, targetCommitId, true);
    }

    @Override
    public void setSelectedGameTime(long targetGameTime, boolean applyToWorld) {
        setSelectedGameTime(targetGameTime, TemporalCommit.NO_PREFERRED_COMMIT, applyToWorld);
    }

    /** Same as {@link #setSelectedGameTime(long, boolean)}, with an explicit target commit id. */
    public JumpResult setSelectedGameTime(long targetGameTime, long targetCommitId, boolean applyToWorld) {
        if (level == null || level.isClientSide) return JumpResult.SUCCESS;

        long min = placedGameTime == UNSET_TIME ? 0L : placedGameTime;
        long max = level.getGameTime();
        long clamped = Math.max(min, Math.min(max, targetGameTime));
        if (selectedGameTime == clamped && !applyToWorld) return JumpResult.SUCCESS;

        long previousSelectedGameTime = selectedGameTime;
        selectedGameTime = clamped;
        JumpResult result = applyToWorld ? applyTimelineView(clamped, targetCommitId, previousSelectedGameTime) : JumpResult.SUCCESS;
        setChanged();
        return result;
    }

    /** Read-only total cost of jumping every chunk to targetGameTime, including the entropy
     * multiplier; kept in sync with {@link #applyTimelineView} so previews never diverge. */
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
        return Math.round(total * jumpCostMultiplier());
    }

    /** Checks out targetGameTime for every chunk and applies it to the live world, paying the
     * combined jump cost from the energy pool first; returns INSUFFICIENT_ENERGY without acting if
     * underfunded. On success, shifts entropy toward chaos for a jump into the past, order for the future. */
    public JumpResult applyTimelineView(long targetGameTime, long targetCommitId, long previousSelectedGameTime) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return JumpResult.SUCCESS;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        TemporalTimeline timeline = worldData.getTimeline(dimension);
        if (timeline == null) return JumpResult.SUCCESS;

        // Flush pending deltas first, or a block placed moments ago could be skipped by the jump below.
        worldData.flushPendingDeltas(level.getGameTime());

        List<ChunkPos> chunks = getAllChunks();
        Predicate<BlockPos> isGlued = pos -> worldData.isGlued(dimension, pos);

        // Capture every chunk's head before branch() moves any of them.
        long[] previousHeads = new long[chunks.size()];
        long totalCost = 0L;
        for (int i = 0; i < chunks.size(); i++) {
            previousHeads[i] = timeline.getChunkHeadId(chunks.get(i));
            totalCost += timeline.computeJumpCost(chunks.get(i), targetGameTime, previousHeads[i], level, AbstractTimelineMachineBlockEntity::costOf, targetCommitId, isGlued);
        }
        totalCost = Math.round(totalCost * jumpCostMultiplier());

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

        if (targetGameTime != previousSelectedGameTime) {
            shiftEntropyToward(targetGameTime < previousSelectedGameTime ? JUMP_ENTROPY_SHIFT : -JUMP_ENTROPY_SHIFT);
        }

        serverLevel.playSound(null, worldPosition, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
        return JumpResult.SUCCESS;
    }

    protected static long costOf(BlockState state) {
        return ItemEnergyCosts.getCost(state.getBlock()).orElse(0);
    }

    @Override
    public long getPlacedGameTime()   { return placedGameTime; }
    @Override
    public long getSelectedGameTime() { return selectedGameTime; }

    // NBT: only energy + per-machine time values; the timeline itself lives in TemporalWorldData.
    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.putLong("PlacedGameTime",   placedGameTime);
        tag.putLong("SelectedGameTime", selectedGameTime);
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        tag.putBoolean("AutoTrackingEnabled", autoTrackingEnabled);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy"))           energyStorage.deserializeNBT(registries, tag.get("Energy"));
        if (tag.contains("PlacedGameTime"))   placedGameTime   = tag.getLong("PlacedGameTime");
        if (tag.contains("SelectedGameTime")) selectedGameTime = tag.getLong("SelectedGameTime");

        if (tag.contains("OrderTank") && tag.contains("ChaosTank")) {
            orderTank.readFromNBT(registries, tag.getCompound("OrderTank"));
            chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        } else if (tag.contains("Entropy")) {
            // Pre-rework save: entropy was a plain 0-1000 int. Rescale x10 into the new 0-10000 tanks.
            int legacyChaos = (int) Math.clamp(tag.getInt("Entropy") * 10L, 0L, (long) ENTROPY_MAX);
            orderTank.setFluid(new FluidStack(Registration.ORDER_FLUID.get(), ENTROPY_MAX - legacyChaos));
            chaosTank.setFluid(new FluidStack(Registration.CHAOS_FLUID.get(), legacyChaos));
        }
        // Absent means a pre-existing save; leave subclass constructor's default in place.
        if (tag.contains("AutoTrackingEnabled")) autoTrackingEnabled = tag.getBoolean("AutoTrackingEnabled");
    }
}
