package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.network.TimelineBlockChangeFlashPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineJumpEffectPacket;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Shared logic between {@link ChronovaultBlockEntity} (one claimed chunk) and
 * {@link ChronosphereBlockEntity} (up to 121, sharing one energy pool). Subclasses only differ in
 * how many chunks they claim ({@link #getAllChunks()}) and energy pool size; everything else
 * (energy storage, game time clamping, the repository-backed {@link TimelineViewProvider}
 * implementation, jump/checkout, NBT) lives here.
 *
 * <p>Each machine owns exactly one {@link TemporalRepository}, keyed by {@link #repositoryId} and
 * held in {@link TemporalWorldData}. Claimed chunks are always captured — there's no auto-tracking
 * toggle any more — and commits only ever happen on explicit player action.
 */
@SuppressWarnings("null")
public abstract class AbstractTimelineMachineBlockEntity extends BlockEntity
        implements net.minecraft.world.MenuProvider, TimelineViewProvider, EntropyInfoProvider {
    protected static final long UNSET_TIME = -1L;

    /** Entropy is a balance between order (0) and chaos ({@link #ENTROPY_MAX}), backed by two
     * FluidTanks whose contents always sum to ENTROPY_MAX. It drifts toward chaos while the
     * selected view sits away from the present, raising jump cost and autotrack drain; a jump
     * into the future adds chaos, into the past adds order. */
    public static final int ENTROPY_MAX = 10000;
    private static final int ENTROPY_BALANCED = ENTROPY_MAX / 2;
    private static final int ENTROPY_DRIFT_INTERVAL_TICKS = 20; // 1 second
    private static final int ENTROPY_DRIFT_STEP = 10; // per interval tick
    private static final int JUMP_ENTROPY_SHIFT = 250; // flat shift per successful jump
    private static final double ENTROPY_COST_SCALE = 1.0; // jump-cost multiplier at max distance from balance
    protected static final long NODE_JUMP_FE_SURCHARGE = 500; // flat extra cost when a jump lands on a different commit than the repository's current head
    private static final int CAPTURE_BASE_FE_PER_TICK = 1;
    private static final double CAPTURE_ENTROPY_SCALE = 4.0; // additional FE/tick at max distance from balance

    protected long placedGameTime = UNSET_TIME;
    protected long selectedGameTime = UNSET_TIME;
    /** This machine's repository identity, generated on first use and persisted forever after.
     * Repositories outlive the machine that made them (see TemporalWorldData), so this is the only
     * handle back to recorded history across a break/replace. */
    @Nullable private UUID repositoryId;

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

    // -------------------------------------------------------------------------
    // Repository access

    /** This machine's repository id, minting one on first call. Persisted via NBT, so a machine
     * keeps its history across reloads. */
    public UUID getRepositoryId() {
        if (repositoryId == null) {
            repositoryId = UUID.randomUUID();
            setChanged();
        }
        return repositoryId;
    }

    /** This machine's repository, created on first access. Null only on the client or before the
     * block entity has a level. */
    @Nullable
    public TemporalRepository getRepository() {
        if (level == null || level.isClientSide || level.getServer() == null) return null;
        return TemporalWorldData.get(level.getServer())
                .getOrCreateRepository(getRepositoryId(), level.dimension().location());
    }

    @Nullable
    protected TemporalWorldData worldData() {
        if (level == null || level.isClientSide || level.getServer() == null) return null;
        return TemporalWorldData.get(level.getServer());
    }

    /** Claims every chunk getAllChunks() returns for this machine's repository. Idempotent, so
     * it's safe to call from onLoad() on every world load. Also captures each chunk's claim
     * baseline (see {@link TemporalRepository#captureClaimBaseline}) so an edit made before this
     * repository's first commit still has a true "before" to roll back to. */
    protected void claimOwnChunks() {
        TemporalWorldData data = worldData();
        if (data == null || !(level instanceof ServerLevel serverLevel)) return;
        ResourceLocation dimension = level.dimension().location();
        TemporalRepository repo = getRepository();
        for (ChunkPos chunk : getAllChunks()) {
            data.claimChunk(dimension, chunk, getRepositoryId());
            if (repo != null) repo.captureClaimBaseline(serverLevel, chunk);
        }
        data.setDirty();
    }

    /** Releases every chunk this machine's repository claims. The repository and its history are
     * untouched — only future capture stops. */
    protected void releaseOwnChunks() {
        TemporalWorldData data = worldData();
        if (data == null) return;
        data.releaseAllClaimedChunks(getRepositoryId());
        data.setDirty();
    }

    /** Commits everything this machine's claimed chunks have accumulated since its last commit,
     * baselining any claimed chunk that doesn't have history yet. Manual only — the new model has
     * no periodic auto-flush. */
    public void commitNow() {
        if (!(level instanceof ServerLevel) || level.getServer() == null) return;
        RepoCommit created = TemporalWorldData.get(level.getServer())
                .commitRepository(level.getServer(), getRepositoryId(), level.getGameTime(), null);
        if (created != null) {
            setChanged();
            syncToClients();
        }
    }

    // -------------------------------------------------------------------------
    // TimelineViewProvider

    @Override
    public List<RepoCommit> getRepositoryCommits() {
        TemporalRepository repo = getRepository();
        return repo == null ? List.of() : new ArrayList<>(repo.getAllCommits());
    }

    @Override
    public Map<String, Long> getBranchTips() {
        TemporalRepository repo = getRepository();
        return repo == null ? Map.of() : repo.getBranchTips();
    }

    @Override
    public long getHeadCommitId() {
        TemporalRepository repo = getRepository();
        return repo == null ? RepoCommit.NO_PARENT : repo.getHeadCommitId();
    }

    /** The branch currently checked out. Switching branches stays entirely implicit: a jump moves
     * the head onto whichever named line it lands on, and committing off a non-tip forks a fresh
     * one — so there's no explicit checkout entry point here. */
    @Override
    public String getCurrentNamedBranch() {
        TemporalRepository repo = getRepository();
        String name = repo == null ? null : repo.getCurrentBranchName();
        return name == null ? "" : name;
    }

    @Override
    public List<ChunkPos> getClaimedChunks() {
        return getAllChunks();
    }

    @Override
    public Map<Long, Long> getJumpCosts(long selectedCommitId) {
        if (selectedCommitId < 0) return Map.of();
        TemporalRepository repo = getRepository();
        if (repo == null || repo.getCommit(selectedCommitId) == null) return Map.of();
        return Map.of(selectedCommitId, computeTotalJumpCost(selectedCommitId));
    }

    @Override
    public Map<ChunkPos, Map<BlockPos, BlockState>> previewJumpTargetStates(long targetCommitId) {
        TemporalRepository repo = getRepository();
        return repo == null ? Map.of() : repo.previewJumpTargetStates(targetCommitId);
    }

    @Override
    public boolean isGluedPos(BlockPos pos) {
        TemporalWorldData data = worldData();
        return data != null && level != null && data.isGlued(level.dimension().location(), pos);
    }

    /** Wipes this machine's repository and re-baselines every claimed chunk from current live
     * state (no blocks change). Also resets placed/selected game time to now. */
    public void deleteAllHistory() {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return;

        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        TemporalRepository repo = data.getOrCreateRepository(getRepositoryId(), level.dimension().location());
        repo.reset(serverLevel, level.getGameTime(), null, getAllChunks());

        placedGameTime = level.getGameTime();
        selectedGameTime = placedGameTime;
        data.setDirty();
        setChanged();
        syncToClients();
    }

    /** Deletes a named branch and the history unique to it. No-op if that branch is the one
     * currently checked out, or doesn't exist. */
    @Override
    public boolean deleteBranch(String branchName) {
        TemporalRepository repo = getRepository();
        TemporalWorldData data = worldData();
        if (repo == null || data == null) return false;

        boolean deleted = repo.deleteBranch(branchName);
        if (deleted) {
            data.setDirty();
            setChanged();
            syncToClients();
        }
        return deleted;
    }

    /** Clamps placed/selected game time and drifts entropy. Called from each subclass's static
     * tick entrypoint. There's no periodic re-snapshot pass any more: each chunk's own delta
     * counter inside {@link TemporalRepository} decides when its next entry must be a full
     * baseline, so nothing needs to be scheduled from out here. */
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

        if (now % ENTROPY_DRIFT_INTERVAL_TICKS == 0) {
            driftEntropy(now);
        }

        drainCaptureEnergy();
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

    /** Per-tick FE drain for keeping claimed chunks under capture, scaled by distance from entropy
     * balance. Unconditional now that the auto-tracking toggle is gone (claimed chunks are always
     * captured), so the running cost follows the claim rather than a flag. Skips rather than
     * stopping capture when energy is insufficient, so history keeps recording. */
    private void drainCaptureEnergy() {
        double distance = Math.abs(getEntropy() - ENTROPY_BALANCED) / (double) ENTROPY_BALANCED;
        int feThisTick = (int) Math.round(CAPTURE_BASE_FE_PER_TICK + CAPTURE_ENTROPY_SCALE * distance);
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

    @Override
    public JumpResult jump(long targetCommitId) {
        if (level == null || level.isClientSide) return JumpResult.SUCCESS;
        return applyTimelineView(targetCommitId);
    }

    @Override
    public void setSelectedCommit(long commitId, boolean applyToWorld) {
        if (level == null || level.isClientSide) return;
        TemporalRepository repo = getRepository();
        RepoCommit target = repo == null ? null : repo.getCommit(commitId);
        if (target == null) return;

        if (applyToWorld) {
            applyTimelineView(commitId);
            return;
        }
        selectedGameTime = clampToPlacedWindow(target.getGameTime());
        setChanged();
    }

    private long clampToPlacedWindow(long gameTime) {
        if (level == null) return gameTime;
        long min = placedGameTime == UNSET_TIME ? 0L : placedGameTime;
        long max = level.getGameTime();
        return Math.max(min, Math.min(max, gameTime));
    }

    /** Read-only total cost of jumping the repository to targetCommitId, including the entropy
     * multiplier and the cross-node surcharge; kept in sync with {@link #applyTimelineView} so
     * previews never diverge from what a jump actually charges. */
    public long computeTotalJumpCost(long targetCommitId) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return 0L;
        TemporalRepository repo = getRepository();
        if (repo == null) return 0L;

        long total = repo.computeJumpCost(targetCommitId, serverLevel,
                AbstractTimelineMachineBlockEntity::costOf, gluePredicate(), NODE_JUMP_FE_SURCHARGE);
        return Math.round(total * jumpCostMultiplier());
    }

    private Predicate<BlockPos> gluePredicate() {
        TemporalWorldData data = worldData();
        if (data == null || level == null) return pos -> false;
        ResourceLocation dimension = level.dimension().location();
        return pos -> data.isGlued(dimension, pos);
    }

    /**
     * Moves the repository's head to targetCommitId and writes the result to the live world,
     * paying the jump cost from the energy pool first; returns INSUFFICIENT_ENERGY without acting
     * if underfunded. Jumping never commits and never forks on its own — forking is lazy, and only
     * happens when the player commits while off a tip (see {@link TemporalRepository#commit}).
     *
     * <p>On success, shifts entropy toward chaos for a jump into the future and order for a jump
     * into the past, with the direction read off the target commit's own recorded gameTime versus
     * the time currently being viewed.
     */
    public JumpResult applyTimelineView(long targetCommitId) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return JumpResult.SUCCESS;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        TemporalRepository repo = worldData.getOrCreateRepository(getRepositoryId(), level.dimension().location());
        RepoCommit target = repo.getCommit(targetCommitId);
        if (target == null) return JumpResult.SUCCESS;

        Predicate<BlockPos> isGlued = gluePredicate();
        long totalCost = Math.round(repo.computeJumpCost(targetCommitId, serverLevel,
                AbstractTimelineMachineBlockEntity::costOf, isGlued, NODE_JUMP_FE_SURCHARGE) * jumpCostMultiplier());

        if (totalCost > energyStorage.getEnergyStored()) {
            serverLevel.playSound(null, worldPosition, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0F, 1.0F);
            return JumpResult.INSUFFICIENT_ENERGY;
        }
        energyStorage.consumeInternal(totalCost);

        // jumpTo already defers neighbour settling until every affected chunk has been written, so
        // a settle pass can't see a sibling chunk in its pre-jump state across a chunk boundary.
        List<BlockPos> changedPositions = repo.jumpTo(targetCommitId, serverLevel, isGlued);
        worldData.setDirty();

        long previousSelectedGameTime = selectedGameTime;
        long targetGameTime = target.getGameTime();
        boolean intoFuture = targetGameTime > previousSelectedGameTime;
        selectedGameTime = clampToPlacedWindow(targetGameTime);
        if (targetGameTime != previousSelectedGameTime) {
            shiftEntropyToward(intoFuture ? JUMP_ENTROPY_SHIFT : -JUMP_ENTROPY_SHIFT);
        }
        setChanged();
        syncToClients();

        serverLevel.playSound(null, worldPosition, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
        PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition),
                new TimelineJumpEffectPacket(worldPosition, intoFuture));
        broadcastBlockChangeFlashes(serverLevel, changedPositions, intoFuture);
        return JumpResult.SUCCESS;
    }

    /** Fans a jump's rewritten positions out as {@link TimelineBlockChangeFlashPacket}s, one per
     * affected chunk so only players actually tracking that chunk receive it, and sampled down if
     * the jump touched an unusually large area so a single jump can't flood the network. */
    private static final int MAX_FLASH_POSITIONS = 1500;

    private static void broadcastBlockChangeFlashes(ServerLevel level, List<BlockPos> changed, boolean intoFuture) {
        if (changed.isEmpty()) return;

        List<BlockPos> sampled = changed;
        if (changed.size() > MAX_FLASH_POSITIONS) {
            sampled = new ArrayList<>(changed);
            Collections.shuffle(sampled);
            sampled = sampled.subList(0, MAX_FLASH_POSITIONS);
        }

        Map<ChunkPos, List<BlockPos>> byChunk = new HashMap<>();
        for (BlockPos pos : sampled) {
            byChunk.computeIfAbsent(new ChunkPos(pos), k -> new ArrayList<>()).add(pos);
        }
        for (Map.Entry<ChunkPos, List<BlockPos>> entry : byChunk.entrySet()) {
            PacketDistributor.sendToPlayersTrackingChunk(level, entry.getKey(),
                    new TimelineBlockChangeFlashPacket(entry.getValue(), intoFuture));
        }
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
        if (repositoryId != null) tag.putUUID("RepositoryId", repositoryId);
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
        // Absent means a pre-rework save (or a machine that's never been ticked); getRepositoryId()
        // mints one lazily on first use rather than forcing one here on the client too.
        if (tag.hasUUID("RepositoryId")) repositoryId = tag.getUUID("RepositoryId");
    }
}
