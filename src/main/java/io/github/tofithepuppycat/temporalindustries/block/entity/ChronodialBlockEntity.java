package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.Chronodial;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Block entity for the Chronodial, the single-block-tier time machine: no continuous change
 * tracking or branching (see TemporalTimeline) — just one manually-set marker on the block it
 * faces, which can later be restored. Deliberately self-contained; unlike the Time Machine it
 * never touches TemporalWorldData/TemporalTimeline.
 *
 * <p>Restoring a marker is always a jump into the past (the marker can only predate the restore),
 * so unlike Chronovault/Chronosphere it always adds a large flat amount of entropy rather than
 * shifting either direction — and refuses the jump outright rather than overflowing the chaos
 * tank. Entropy here has no passive drift, and moves only via jumps or fluid piped into its tanks.
 */
@SuppressWarnings("null")
public class ChronodialBlockEntity extends BlockEntity implements EntropyInfoProvider {
    private static final int ENERGY_CAPACITY = 10_000;
    private static final int ENERGY_TRANSFER  = 500;
    private static final long UNSET_TIME = -1L;

    public static final int ENTROPY_MAX = 10000;
    private static final int ENTROPY_BALANCED = ENTROPY_MAX / 2;
    private static final double ENTROPY_COST_SCALE = 1.0;
    private static final int JUMP_ENTROPY_SHIFT = 2000; // large flat shift — a full marker restore, not a partial view

    public enum JumpResult { SUCCESS, ALREADY_AT_MARKER, NO_MARKER, INSUFFICIENT_ENERGY, ENTROPY_MAXED }

    private long markerGameTime = UNSET_TIME;
    @Nullable private BlockState markerState;
    @Nullable private CompoundTag markerBlockEntityTag;

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

    /** Named (rather than anonymous) so jumpToMarker() can reach consumeInternal() directly,
     * bypassing the maxExtract cap that only throttles external cables/pipes. */
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

    public ChronodialBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.CHRONODIAL_BLOCK_ENTITY.get(), pos, state);
        orderTank.setFluid(new FluidStack(Registration.ORDER_FLUID.get(), ENTROPY_BALANCED));
        chaosTank.setFluid(new FluidStack(Registration.CHAOS_FLUID.get(), ENTROPY_BALANCED));
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public IFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public FluidTank getOrderTank() {
        return orderTank;
    }

    public FluidTank getChaosTank() {
        return chaosTank;
    }

    public int getEntropy() {
        return chaosTank.getFluidAmount();
    }

    private double jumpCostMultiplier() {
        double distance = Math.abs(getEntropy() - ENTROPY_BALANCED) / (double) ENTROPY_BALANCED;
        return 1.0 + ENTROPY_COST_SCALE * distance;
    }

    @Override public boolean hasEntropyBalance() { return true; }
    @Override public int getEntropyBalance() { return getEntropy(); }
    @Override public int getEntropyBalanceMax() { return ENTROPY_MAX; }

    /** Reports the marked block (and whether it carries block-entity data), plus the FE and
     * entropy cost of restoring it right now — visible only while wearing Entropy Glasses. */
    @Override
    public List<Component> getEntropyTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("block.temporalindustries.chronodial").withStyle(ChatFormatting.WHITE));

        if (markerState == null) {
            lines.add(Component.translatable("block.temporalindustries.chronodial.no_marker").withStyle(ChatFormatting.GRAY));
            return lines;
        }

        Component blockName = markerState.getBlock().getName();
        lines.add(Component.translatable("block.temporalindustries.chronodial.glasses_marked_block", blockName)
                .withStyle(ChatFormatting.AQUA));
        if (markerBlockEntityTag != null) {
            lines.add(Component.translatable("block.temporalindustries.chronodial.glasses_has_data").withStyle(ChatFormatting.GRAY));
        }

        long feCost = Math.round(ItemEnergyCosts.getCost(markerState.getBlock()).orElse(0) * jumpCostMultiplier());
        lines.add(Component.translatable("block.temporalindustries.chronodial.glasses_fe_cost", feCost).withStyle(ChatFormatting.YELLOW));
        lines.add(Component.translatable("block.temporalindustries.chronodial.glasses_entropy_cost", EntropyDisplay.formatBalance(JUMP_ENTROPY_SHIFT))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return lines;
    }

    /** The block this Chronodial reads/writes: whichever position its front face points at. */
    public BlockPos getTargetPos() {
        return worldPosition.relative(getBlockState().getValue(Chronodial.FACING));
    }

    public boolean hasMarker() {
        return markerGameTime != UNSET_TIME;
    }

    public long getMarkerGameTime() {
        return markerGameTime;
    }

    /** Captures the target block's current state (and block entity data, if any) as the marker. */
    public void setMarker() {
        if (level == null || level.isClientSide) return;

        BlockPos targetPos = getTargetPos();
        BlockEntity targetBe = level.getBlockEntity(targetPos);

        markerState = level.getBlockState(targetPos);
        markerBlockEntityTag = targetBe != null ? targetBe.saveWithFullMetadata(level.registryAccess()) : null;
        markerGameTime = level.getGameTime();
        setChanged();
        syncToClients();
    }

    /** Restores the target block to its marked state, paying the jump's energy cost first (scaled
     * by {@link #jumpCostMultiplier()}) and refusing outright if restoring would overflow the
     * chaos tank, rather than clamping and restoring anyway. */
    public JumpResult jumpToMarker() {
        if (level == null || level.isClientSide || markerState == null) return JumpResult.NO_MARKER;

        BlockPos targetPos = getTargetPos();
        if (level.getBlockState(targetPos).equals(markerState)) return JumpResult.ALREADY_AT_MARKER;

        if (chaosTank.getFluidAmount() + JUMP_ENTROPY_SHIFT > ENTROPY_MAX) return JumpResult.ENTROPY_MAXED;

        long cost = Math.round(ItemEnergyCosts.getCost(markerState.getBlock()).orElse(0) * jumpCostMultiplier());
        if (cost > energyStorage.getEnergyStored()) return JumpResult.INSUFFICIENT_ENERGY;

        energyStorage.consumeInternal((int) Math.min(cost, Integer.MAX_VALUE));

        level.setBlock(targetPos, markerState, 3);
        BlockEntity be = level.getBlockEntity(targetPos);
        if (be != null && markerBlockEntityTag != null) {
            CompoundTag restored = markerBlockEntityTag.copy();
            restored.putInt("x", targetPos.getX());
            restored.putInt("y", targetPos.getY());
            restored.putInt("z", targetPos.getZ());
            be.loadWithComponents(restored, level.registryAccess());
            be.setChanged();
        }

        chaosTank.fill(new FluidStack(Registration.CHAOS_FLUID.get(), JUMP_ENTROPY_SHIFT), IFluidHandler.FluidAction.EXECUTE);
        orderTank.drain(JUMP_ENTROPY_SHIFT, IFluidHandler.FluidAction.EXECUTE);
        setChanged();
        syncToClients();

        return JumpResult.SUCCESS;
    }

    // -------------------------------------------------------------------------
    // Sync

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

    // -------------------------------------------------------------------------
    // NBT

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        tag.putLong("MarkerGameTime", markerGameTime);
        if (markerState != null) {
            BlockState.CODEC.encodeStart(NbtOps.INSTANCE, markerState).result()
                    .ifPresent(nbt -> tag.put("MarkerState", nbt));
        }
        if (markerBlockEntityTag != null) {
            tag.put("MarkerBE", markerBlockEntityTag.copy());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy")) energyStorage.deserializeNBT(registries, tag.get("Energy"));
        if (tag.contains("OrderTank")) orderTank.readFromNBT(registries, tag.getCompound("OrderTank"));
        if (tag.contains("ChaosTank")) chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        markerGameTime = tag.contains("MarkerGameTime") ? tag.getLong("MarkerGameTime") : UNSET_TIME;
        markerState = tag.contains("MarkerState")
                ? BlockState.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("MarkerState")).result().orElse(Blocks.AIR.defaultBlockState())
                : null;
        markerBlockEntityTag = tag.contains("MarkerBE") ? tag.getCompound("MarkerBE").copy() : null;
    }
}
