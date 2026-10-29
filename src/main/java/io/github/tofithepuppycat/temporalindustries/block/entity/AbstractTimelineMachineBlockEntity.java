package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.network.TimelineBlockChangeFlashPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineJumpEffectPacket;
import io.github.tofithepuppycat.temporalindustries.timeline.JumpCostBreakdown;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Shared logic between {@link ChronovaultBlockEntity} (one claimed chunk) and {@link
 * ChronosphereBlockEntity} (up to 121, sharing one energy pool).
 */
@SuppressWarnings("null")
public abstract class AbstractTimelineMachineBlockEntity extends BlockEntity
        implements net.minecraft.world.MenuProvider, TimelineViewProvider, EntropyInfoProvider {
    protected static final long UNSET_TIME = -1L;

    /**
     * Entropy is a balance between order (0) and chaos ({@link #ENTROPY_MAX}), backed by two
     * FluidTanks whose contents always sum to ENTROPY_MAX.
     */
    public static final int ENTROPY_MAX = 10000;
    private static final int ENTROPY_BALANCED = ENTROPY_MAX / 2;
    private static final long JUMP_FE_PER_ENTROPY_UNIT = 10; // jump FE cost that moves the balance one unit toward chaos
    public static final double JUMP_TIME_FE_SCALE = 250.0; // FE per natural-log unit of minutes between now and the target commit
    private static final double TICKS_PER_MINUTE = 1200.0;
    public static final double JUMP_ENTROPIC_GROWTH = 8.0; // steepness of the exponential curve; higher keeps low chaos cheaper for longer
    public static final long JUMP_ENTROPIC_MAX_FE = 5_000_000L;
    /**
     * Coefficient of the entropic curve, chosen so full chaos costs exactly JUMP_ENTROPIC_MAX_FE; zero
     * at full order.
     */
    public static final double JUMP_ENTROPIC_FE_SCALE = JUMP_ENTROPIC_MAX_FE / Math.expm1(JUMP_ENTROPIC_GROWTH);
    public static final double JUMP_ENTITY_FE_PER_HEALTH = 100.0; // FE per health point of every entity a jump restores or removes
    private static final int CAPTURE_BASE_FE_PER_TICK = 1;
    private static final double CAPTURE_ENTROPY_SCALE = 4.0; // additional FE/tick at max distance from balance

    protected long placedGameTime = UNSET_TIME;
    protected long selectedGameTime = UNSET_TIME;
    /** This machine's repository identity, generated on first use and persisted forever after. */
    @Nullable private UUID repositoryId;
    /** Mirror of the repository's name, kept here so the client's synced copy knows it. */
    private String repositoryName = "";
    /**
     * Mirror of the repository's head commit, kept here so the client's synced copy can describe
     * it.
     */
    private String headCommitHash = "";
    private long headCommitGameTime = UNSET_TIME;
    private String headBranchName = "";

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

        /**
         * Filling one tank displaces the same amount from the other, keeping orderTank+chaosTank
         * fixed at ENTROPY_MAX.
         */
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

        /**
         * Draining one tank grows the other by the same amount, same fixed-sum invariant as {@link
         * #fill}.
         */
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

    /**
     * Named so a jump's own cost can be deducted directly, bypassing the maxExtract cap that only
     * throttles external capability access.
     */
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

        void addInternal(long amount) {
            if (amount <= 0) return;
            energy = (int) Math.min(capacity, energy + amount);
            setChanged();
        }

        void fillInternal() {
            if (energy >= capacity) return;
            energy = capacity;
            setChanged();
        }
    }

    private final MachineEnergyStorage energyStorage;
    private final ContainerData data;

    /**
     * A jump whose cost exceeds the energy stored is paid in instalments: every tick the pool is
     * moved into {@link #charged} until {@link #chargeCost} is covered, then the jump runs.
     */
    private boolean saveEntities = false;
    private boolean chargeContainers = true;
    private boolean charging;
    private long chargeTarget;
    private long chargeCost;
    private long charged;

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
                    case 16 -> charging ? 1 : 0;
                    case 17 -> chargeProgressPermille();
                    default -> 0;
                };
            }
            @Override public void set(int index, int value) {}
            @Override public int getCount() { return 18; }
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
        List<Component> lines = new ArrayList<>();
        lines.add(getDisplayName().copy().withStyle(ChatFormatting.WHITE));
        if (repositoryName.isEmpty()) {
            lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.timeline.unbound").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.timeline.repository", repositoryName)
                .withStyle(ChatFormatting.AQUA));

        long now = level == null ? 0L : level.getGameTime();
        if (headCommitGameTime == UNSET_TIME) {
            lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.timeline.no_commits").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.timeline.head", headCommitHash,
                    headBranchName.isEmpty() ? "-" : headBranchName).withStyle(ChatFormatting.WHITE));
            lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.timeline.committed",
                    formatDuration(now - headCommitGameTime)).withStyle(ChatFormatting.GRAY));
        }
        if (selectedGameTime != UNSET_TIME) {
            lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.timeline.viewing",
                    selectedGameTime >= now ? Component.translatable("overlay.temporalindustries.entropy_glasses.timeline.present").getString()
                            : formatDuration(now - selectedGameTime) + " ago").withStyle(ChatFormatting.YELLOW));
        }
        return lines;
    }

    /** Game ticks as a compact "1h 2m 3s" style duration, dropping leading zero units. */
    private static String formatDuration(long ticks) {
        long seconds = Math.max(0L, ticks) / 20L;
        long hours = seconds / 3600L;
        long minutes = (seconds / 60L) % 60L;
        long secs = seconds % 60L;
        if (hours > 0) return hours + "h " + minutes + "m " + secs + "s";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }

    @Override public boolean hasEntropyBalance() { return true; }
    @Override public int getEntropyBalance() { return getEntropy(); }
    @Override public int getEntropyBalanceMax() { return ENTROPY_MAX; }

    /**
     * Transfers up to |delta| units from order to chaos (delta > 0) or vice versa, clamped to [0,
     * ENTROPY_MAX].
     */
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

    /**
     * Base jump cost, growing logarithmically with how far the target commit lies from the present
     * in either direction.
     */
    private long timeDistanceCost(RepoCommit target, long now) {
        double minutes = Math.abs(now - target.getGameTime()) / TICKS_PER_MINUTE;
        return Math.round(JUMP_TIME_FE_SCALE * Math.log1p(minutes));
    }

    /**
     * Flat cost that scales exponentially with chaos: free at full order, capped at
     * Integer.MAX_VALUE.
     */
    private long entropicCost() {
        double chaos = getEntropy() / (double) ENTROPY_MAX;
        return Math.min(JUMP_ENTROPIC_MAX_FE, Math.round(JUMP_ENTROPIC_FE_SCALE * Math.expm1(JUMP_ENTROPIC_GROWTH * chaos)));
    }

    /**
     * Whether pos lies in a claimed chunk but outside the part of it this machine records: such
     * blocks are neither captured nor restored, exactly like anti-glued ones.
     */
    private boolean isOutsideTrackedArea(BlockPos pos) {
        TemporalRepository repo = getRepository();
        return repo != null && !repo.isTracked(pos);
    }

    /** Every chunk a jump on this machine moves together, home/primary chunk first. */
    public abstract List<ChunkPos> getAllChunks();


    /** Whether this machine gets a fresh repository on first use. */
    protected boolean mintsRepositoryOnDemand() {
        return true;
    }

    /**
     * This machine's repository id, minting one on first call if {@link #mintsRepositoryOnDemand}.
     */
    @Nullable
    public UUID getRepositoryId() {
        if (repositoryId == null && mintsRepositoryOnDemand()) {
            repositoryId = UUID.randomUUID();
            setChanged();
        }
        return repositoryId;
    }

    public boolean isRepositoryBound() {
        return getRepositoryId() != null;
    }

    /** The bound repository's name as last synced, or empty while unbound or not yet known. */
    public String getRepositoryName() {
        return repositoryName;
    }

    /** Points this machine at repoId and starts claiming its chunks for it. */
    protected void bindRepository(UUID repoId, String name) {
        repositoryId = repoId;
        repositoryName = name;
        claimOwnChunks();
        setChanged();
        syncToClients();
    }

    /** Frees this machine's repository for another machine to bind by name. */
    public void orphanRepository() {
        TemporalWorldData data = worldData();
        if (data == null || repositoryId == null) return;
        data.clearRepositoryOwner(repositoryId);
    }

    /**
     * Called when this machine is broken: frees its repository, and drops it entirely if it never
     * recorded history and still has its automatic name, since nothing could be bound back to it.
     * Energy already moved aside for a charging jump goes back into the pool, so it travels with
     * the dropped item.
     */
    public void releaseRepositoryOnBreak() {
        cancelCharging();
        TemporalWorldData data = worldData();
        if (data == null || repositoryId == null) return;
        data.clearRepositoryOwner(repositoryId);
        data.discardRepositoryIfUnused(repositoryId);
    }

    /** This machine's repository, created on first access. */
    @Nullable
    public TemporalRepository getRepository() {
        UUID repoId = getRepositoryId();
        if (repoId == null || level == null || level.isClientSide || level.getServer() == null) return null;
        TemporalRepository repo = TemporalWorldData.get(level.getServer())
                .getOrCreateRepository(repoId, level.dimension().location());
        if (!repo.getName().equals(repositoryName)) {
            repositoryName = repo.getName();
            setChanged();
        }
        return repo;
    }

    @Nullable
    protected TemporalWorldData worldData() {
        if (level == null || level.isClientSide || level.getServer() == null) return null;
        return TemporalWorldData.get(level.getServer());
    }

    /** Claims every chunk getAllChunks() returns for this machine's repository. */
    protected void claimOwnChunks() {
        TemporalWorldData data = worldData();
        UUID repoId = getRepositoryId();
        if (data == null || repoId == null || !(level instanceof ServerLevel serverLevel)) return;
        ResourceLocation dimension = level.dimension().location();
        TemporalRepository repo = getRepository();
        data.setRepositoryOwner(repoId, GlobalPos.of(level.dimension(), worldPosition));
        for (ChunkPos chunk : getAllChunks()) {
            if (data.claimChunk(dimension, chunk, repoId) == TemporalWorldData.ClaimResult.ALREADY_CLAIMED_ELSEWHERE) {
                onChunkClaimLost(chunk);
                continue;
            }
            if (repo != null) repo.captureClaimBaseline(serverLevel, chunk);
        }
        data.setDirty();
        setChanged();
    }

    /** Called when a chunk this machine used to control is now claimed by another repository. */
    protected void onChunkClaimLost(ChunkPos chunk) {
    }

    /** Releases every chunk this machine's repository claims. */
    protected void releaseOwnChunks() {
        TemporalWorldData data = worldData();
        if (data == null || repositoryId == null) return;
        data.releaseAllClaimedChunks(repositoryId);
        data.setDirty();
    }

    /**
     * Commits everything this machine's claimed chunks have accumulated since its last commit,
     * baselining any claimed chunk that doesn't have history yet.
     */
    public void commitNow(@Nullable ServerPlayer requester) {
        UUID repoId = getRepositoryId();
        if (repoId == null || !(level instanceof ServerLevel) || level.getServer() == null) return;
        TemporalWorldData.CommitStart start = TemporalWorldData.get(level.getServer()).beginRepositoryCommit(
                level.getServer(), repoId, level.getGameTime(), null, requester == null ? null : requester.getUUID(),
                created -> {
                    if (created == null || isRemoved()) return;
                    setChanged();
                    syncToClients();
                });
        if (requester == null) return;
        switch (start) {
            case NOTHING_TO_COMMIT -> requester.displayClientMessage(
                    Component.translatable("gui.temporalindustries.chrono_remote.msg.nothing_to_commit"), true);
            case ALREADY_RUNNING -> requester.displayClientMessage(
                    Component.translatable("gui.temporalindustries.timeline_view.commit_busy"), true);
            default -> { }
        }
    }

    /** Whether this machine's repository is still capturing a commit, which freezes its history. */
    public boolean isCommitInProgress() {
        TemporalWorldData data = worldData();
        return data != null && repositoryId != null && data.isCommitInProgress(repositoryId);
    }


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

    /** The branch currently checked out. */
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
    public Map<Long, JumpCostBreakdown> getJumpCosts(long selectedCommitId) {
        if (selectedCommitId < 0) return Map.of();
        TemporalRepository repo = getRepository();
        if (repo == null || repo.getCommit(selectedCommitId) == null) return Map.of();
        return Map.of(selectedCommitId, computeJumpCostBreakdown(selectedCommitId));
    }

    @Override
    public Map<ChunkPos, Map<BlockPos, BlockState>> previewJumpTargetStates(long targetCommitId) {
        TemporalRepository repo = getRepository();
        return repo == null ? Map.of() : repo.previewJumpTargetStates(targetCommitId);
    }

    @Override
    public java.util.Set<ChunkPos> previewAffectedChunks(long targetCommitId) {
        TemporalRepository repo = getRepository();
        return repo == null ? java.util.Set.of() : repo.previewAffectedChunks(targetCommitId);
    }

    @Override
    public Map<BlockPos, BlockState> previewChunkTargetStates(long targetCommitId, ChunkPos chunk) {
        TemporalRepository repo = getRepository();
        return repo == null ? Map.of() : repo.previewChunkTargetStates(targetCommitId, chunk);
    }

    @Override
    public boolean isGluedPos(BlockPos pos) {
        TemporalWorldData data = worldData();
        if (pos.equals(worldPosition)) return true;
        if (isOutsideTrackedArea(pos)) return true;
        return data != null && level != null && data.isGlued(level.dimension().location(), pos);
    }

    public enum BindResult { CREATED, ADOPTED, IN_USE, WRONG_DIMENSION, INVALID_NAME, ALREADY_BOUND, UNAVAILABLE }

    /**
     * Binds this machine to the repository called rawName, creating an empty one if no such
     * repository exists yet.
     */
    public BindResult bindToRepositoryNamed(String rawName) {
        if (!(level instanceof ServerLevel) || level.getServer() == null) return BindResult.UNAVAILABLE;
        if (isRepositoryBound()) return BindResult.ALREADY_BOUND;

        String name = TemporalWorldData.normalizeRepositoryName(rawName);
        if (name == null) return BindResult.INVALID_NAME;

        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        UUID existingId = data.findRepositoryByName(name);
        if (existingId == null) {
            UUID created = data.createRepository(name, level.dimension().location());
            placedGameTime = level.getGameTime();
            selectedGameTime = placedGameTime;
            bindRepository(created, name);
            return BindResult.CREATED;
        }

        TemporalRepository existing = data.getRepository(existingId);
        if (existing == null) return BindResult.UNAVAILABLE;
        if (data.hasRepositoryOwner(existingId)) return BindResult.IN_USE;
        if (!existing.getDimension().equals(level.dimension().location())) return BindResult.WRONG_DIMENSION;

        // The adopted history predates this block, so its selectable window starts at its root.
        RepoCommit root = existing.getCommit(existing.getRootCommitId());
        placedGameTime = root != null ? root.getGameTime() : level.getGameTime();
        selectedGameTime = level.getGameTime();
        bindRepository(existingId, existing.getName());
        return BindResult.ADOPTED;
    }

    /**
     * Switches this machine to the repository called rawName (created if new), detaching from the
     * current one first. Nothing changes unless the new repository can actually be bound.
     */
    public BindResult changeRepositoryTo(String rawName) {
        if (!(level instanceof ServerLevel) || level.getServer() == null) return BindResult.UNAVAILABLE;

        String name = TemporalWorldData.normalizeRepositoryName(rawName);
        if (name == null) return BindResult.INVALID_NAME;

        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        UUID existingId = data.findRepositoryByName(name);
        if (existingId != null) {
            if (existingId.equals(repositoryId)) return BindResult.ALREADY_BOUND;
            TemporalRepository existing = data.getRepository(existingId);
            if (existing == null) return BindResult.UNAVAILABLE;
            if (data.hasRepositoryOwner(existingId)) return BindResult.IN_USE;
            if (!existing.getDimension().equals(level.dimension().location())) return BindResult.WRONG_DIMENSION;
        }

        if (isRepositoryBound()) {
            if (isCommitInProgress()) return BindResult.UNAVAILABLE;
            unbindRepository();
        }
        return bindToRepositoryNamed(name);
    }

    /**
     * Detaches this machine from its repository without touching the repository or its history: its
     * chunk claims are released and the repository becomes free for any machine to bind by name.
     */
    public void unbindRepository() {
        if (!(level instanceof ServerLevel) || level.getServer() == null || repositoryId == null) return;

        releaseOwnChunks();
        orphanRepository();
        repositoryId = null;
        repositoryName = "";

        if (mintsRepositoryOnDemand()) claimOwnChunks();
        setChanged();
        syncToClients();
    }

    /**
     * Permanently deletes the bound repository and unbinds this machine from it (no blocks change).
     */
    public void deleteRepository() {
        if (!(level instanceof ServerLevel) || level.getServer() == null || repositoryId == null) return;
        if (isCommitInProgress()) return;

        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        data.deleteRepository(repositoryId);
        repositoryId = null;
        repositoryName = "";

        placedGameTime = level.getGameTime();
        selectedGameTime = placedGameTime;
        if (mintsRepositoryOnDemand()) claimOwnChunks();
        setChanged();
        syncToClients();
    }

    /** Deletes a commit and everything descended from it. */
    @Override
    public boolean deleteCommit(long commitId) {
        TemporalRepository repo = getRepository();
        TemporalWorldData data = worldData();
        if (repo == null || data == null || isCommitInProgress()) return false;

        boolean deleted = repo.deleteCommit(commitId);
        if (deleted) {
            data.setDirty();
            setChanged();
            syncToClients();
        }
        return deleted;
    }

    /** Clamps placed/selected game time and drains capture energy. */
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

        tickCharging();
        drainCaptureEnergy();
    }

    public boolean isCharging() {
        return charging;
    }

    private int chargeProgressPermille() {
        if (!charging || chargeCost <= 0) return 0;
        return (int) Math.min(1000L, charged * 1000L / chargeCost);
    }

    private void beginCharging(long targetCommitId, long cost) {
        cancelCharging();
        charging = true;
        chargeTarget = targetCommitId;
        chargeCost = cost;
        charged = 0L;
        setChanged();
    }

    /** Stops charging and returns whatever was already collected to the energy pool. */
    private void cancelCharging() {
        if (!charging) return;
        energyStorage.addInternal(charged);
        charging = false;
        charged = 0L;
        setChanged();
    }

    private void tickCharging() {
        if (!charging) return;
        TemporalRepository repo = getRepository();
        RepoCommit target = repo == null ? null : repo.getCommit(chargeTarget);
        if (target == null) {
            cancelCharging();
            return;
        }

        long take = Math.min(chargeCost - charged, energyStorage.getEnergyStored());
        if (take > 0) {
            energyStorage.consumeInternal(take);
            charged += take;
            setChanged();
        }
        if (charged < chargeCost) return;

        long paid = chargeCost;
        charging = false;
        charged = 0L;
        performJump(repo, target, chargeTarget, paid);
    }

    /**
     * Per-tick FE drain for keeping claimed chunks under capture, scaled by distance from entropy
     * balance.
     */
    private void drainCaptureEnergy() {
        if (isCreative()) {
            energyStorage.fillInternal();
            return;
        }
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

    /**
     * Read-only cost of jumping the repository to targetCommitId: the time-distance cost, the cost
     * of every changed block, and the entropic cost.
     */
    public JumpCostBreakdown computeJumpCostBreakdown(long targetCommitId) {
        JumpCostBreakdown free = new JumpCostBreakdown(0L, 0L, 0L, 0L, 0L, 0.0, 0L, 0, 0.0, 0.0, 0.0,
                savesEntities(), chargesContainers());
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return free;
        TemporalRepository repo = getRepository();
        RepoCommit target = repo == null ? null : repo.getCommit(targetCommitId);
        if (target == null || targetCommitId == repo.getHeadCommitId() || isCreative()) return free;

        // Estimate only: unloaded chunks are skipped so a GUI poll can't force-load the claim.
        AtomicLong changedBlocks = new AtomicLong();
        long blocks = repo.computeJumpCost(targetCommitId, serverLevel, state -> {
            changedBlocks.incrementAndGet();
            return costOf(state);
        }, gluePredicate(), true);
        double minutes = Math.abs(serverLevel.getGameTime() - target.getGameTime()) / TICKS_PER_MINUTE;
        TemporalRepository.EntityJumpStats entityStats = savesEntities()
                ? repo.computeEntityJumpStats(targetCommitId, serverLevel)
                : new TemporalRepository.EntityJumpStats(0, 0.0);
        TemporalRepository.ContainerJumpStats containerStats = chargesContainers()
                ? repo.computeContainerJumpStats(targetCommitId, serverLevel, AbstractTimelineMachineBlockEntity::itemCostOf,
                        gluePredicate(), true)
                : TemporalRepository.ContainerJumpStats.NONE;
        return new JumpCostBreakdown(timeDistanceCost(target, serverLevel.getGameTime()), blocks,
                entityCost(entityStats), containerStats.cost(), entropicCost(), minutes, changedBlocks.get(),
                entityStats.count(), entityStats.health(), containerStats.slots(),
                getEntropy() / (double) ENTROPY_MAX, savesEntities(), chargesContainers());
    }

    /** Creative machines jump for free, never lose energy, and never shift entropy. */
    protected boolean isCreative() {
        return false;
    }

    /** Whether jumps on this machine also restore the entities recorded in the target commit. */
    protected boolean savesEntities() {
        return saveEntities;
    }

    public boolean isSaveEntities() {
        return saveEntities;
    }

    public void setSaveEntities(boolean value) {
        if (saveEntities == value) return;
        saveEntities = value;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Whether jumps on this machine also charge for the container contents they add to the world. */
    protected boolean chargesContainers() {
        return chargeContainers;
    }

    public boolean isChargeContainers() {
        return chargeContainers;
    }

    public void setChargeContainers(boolean value) {
        if (chargeContainers == value) return;
        chargeContainers = value;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static long entityCost(TemporalRepository.EntityJumpStats stats) {
        return Math.round(stats.health() * JUMP_ENTITY_FE_PER_HEALTH);
    }

    private Predicate<BlockPos> gluePredicate() {
        TemporalWorldData data = worldData();
        if (data == null || level == null) return worldPosition::equals;
        ResourceLocation dimension = level.dimension().location();
        BlockPos machinePos = worldPosition;
        TemporalRepository repo = getRepository();
        return pos -> pos.equals(machinePos) || data.isGlued(dimension, pos) || (repo != null && !repo.isTracked(pos));
    }

    /**
     * Moves the repository's head to targetCommitId and writes the result to the live world, paying
     * the jump cost from the energy pool first; if the pool cannot cover it, starts charging and
     * returns CHARGING, and the jump runs once enough energy has been collected.
     */
    public JumpResult applyTimelineView(long targetCommitId) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return JumpResult.SUCCESS;
        if (isCommitInProgress()) return JumpResult.BUSY;

        if (charging && chargeTarget == targetCommitId) return JumpResult.CHARGING;

        TemporalRepository repo = getRepository();
        RepoCommit target = repo == null ? null : repo.getCommit(targetCommitId);
        if (target == null) return JumpResult.SUCCESS;

        Predicate<BlockPos> isGlued = gluePredicate();
        boolean restoreEntities = savesEntities();
        long totalCost = 0L;
        if (targetCommitId != repo.getHeadCommitId() && !isCreative()) {
            totalCost = timeDistanceCost(target, serverLevel.getGameTime())
                    + repo.computeJumpCost(targetCommitId, serverLevel, AbstractTimelineMachineBlockEntity::costOf, isGlued)
                    + (restoreEntities ? entityCost(repo.computeEntityJumpStats(targetCommitId, serverLevel)) : 0L)
                    + (chargesContainers() ? repo.computeContainerJumpStats(targetCommitId, serverLevel,
                            AbstractTimelineMachineBlockEntity::itemCostOf, isGlued, false).cost() : 0L)
                    + entropicCost();
        }

        if (totalCost > energyStorage.getEnergyStored()) {
            beginCharging(targetCommitId, totalCost);
            serverLevel.playSound(null, worldPosition, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.0F);
            return JumpResult.CHARGING;
        }
        cancelCharging();
        energyStorage.consumeInternal(totalCost);
        performJump(repo, target, targetCommitId, totalCost);
        return JumpResult.SUCCESS;
    }

    /** Writes the jump to the live world once its cost has been paid. */
    private void performJump(TemporalRepository repo, RepoCommit target, long targetCommitId, long totalCost) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) return;
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        Predicate<BlockPos> isGlued = gluePredicate();
        boolean restoreEntities = savesEntities();

        // jumpTo already defers neighbour settling until every affected chunk has been written, so
        // a settle pass can't see a sibling chunk in its pre-jump state across a chunk boundary.
        List<BlockPos> changedPositions = repo.jumpTo(targetCommitId, serverLevel, isGlued, restoreEntities);
        worldData.discardPendingEntityDeltas(getRepositoryId());
        worldData.setDirty();

        long previousSelectedGameTime = selectedGameTime;
        long targetGameTime = target.getGameTime();
        boolean intoFuture = targetGameTime > previousSelectedGameTime;
        selectedGameTime = clampToPlacedWindow(targetGameTime);
        shiftEntropyToward((int) Math.min(ENTROPY_MAX, totalCost / JUMP_FE_PER_ENTROPY_UNIT));
        setChanged();
        syncToClients();

        serverLevel.playSound(null, worldPosition, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
        PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition),
                new TimelineJumpEffectPacket(worldPosition, intoFuture));
        broadcastBlockChangeFlashes(serverLevel, changedPositions, intoFuture);
    }

    /**
     * Fans a jump's rewritten positions out as {@link TimelineBlockChangeFlashPacket}s, one per
     * affected chunk so only players actually tracking that chunk receive it, and sampled down if
     * the jump touched an unusually large area so a single jump can't flood the network.
     */
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

    protected static long itemCostOf(Item item) {
        return ItemEnergyCosts.getCost(item).orElse(0);
    }

    @Override
    public long getPlacedGameTime()   { return placedGameTime; }
    @Override
    public long getSelectedGameTime() { return selectedGameTime; }

    // NBT: only energy + per-machine time values; the timeline itself lives in TemporalWorldData.
    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        saveCarriedState(tag, registries);
        tag.putLong("PlacedGameTime",   placedGameTime);
        tag.putLong("SelectedGameTime", selectedGameTime);
        if (repositoryId != null) tag.putUUID("RepositoryId", repositoryId);
        tag.putString("RepositoryName", repositoryName);
        tag.putBoolean("Charging", charging);
        tag.putLong("ChargeTarget", chargeTarget);
        tag.putLong("ChargeCost", chargeCost);
        tag.putLong("Charged", charged);
        writeHeadCommit(tag);
    }

    /** The state that stays with a machine when it is picked up and placed again. */
    protected void saveCarriedState(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        tag.putBoolean("SaveEntities", saveEntities);
        tag.putBoolean("ChargeContainers", chargeContainers);
    }

    /**
     * Carries energy, entropy and settings onto the dropped item; the repository stays behind,
     * since it is tied to this position.
     */
    @Override
    protected void collectImplicitComponents(@NotNull DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (level == null) return;
        CompoundTag data = new CompoundTag();
        saveCarriedState(data, level.registryAccess());
        BlockEntity.addEntityType(data, getType());
        components.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
    }

    /**
     * Server side only: stamps the repository's head commit into the tag so clients can describe
     * it.
     */
    private void writeHeadCommit(CompoundTag tag) {
        if (repositoryId == null || level == null || level.isClientSide) return;
        TemporalRepository repo = getRepository();
        RepoCommit head = repo == null ? null : repo.getCommit(repo.getHeadCommitId());
        if (head == null) return;
        tag.putString("HeadCommitHash", head.getShortHash());
        tag.putLong("HeadCommitGameTime", head.getGameTime());
        String branch = repo.getCurrentBranchName();
        tag.putString("HeadBranchName", branch == null ? "" : branch);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy"))           energyStorage.deserializeNBT(registries, tag.get("Energy"));
        if (tag.contains("PlacedGameTime"))   placedGameTime   = tag.getLong("PlacedGameTime");
        if (tag.contains("SelectedGameTime")) selectedGameTime = tag.getLong("SelectedGameTime");
        saveEntities = tag.getBoolean("SaveEntities");
        chargeContainers = !tag.contains("ChargeContainers") || tag.getBoolean("ChargeContainers");
        charging = tag.getBoolean("Charging");
        chargeTarget = tag.getLong("ChargeTarget");
        chargeCost = tag.getLong("ChargeCost");
        charged = tag.getLong("Charged");

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
        repositoryId = tag.hasUUID("RepositoryId") ? tag.getUUID("RepositoryId") : null;
        repositoryName = tag.getString("RepositoryName");
        headCommitHash = tag.getString("HeadCommitHash");
        headCommitGameTime = tag.contains("HeadCommitGameTime") ? tag.getLong("HeadCommitGameTime") : UNSET_TIME;
        headBranchName = tag.getString("HeadBranchName");
    }
}
