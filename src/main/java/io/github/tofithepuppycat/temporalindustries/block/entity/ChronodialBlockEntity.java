package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.Chronodial;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * Block entity for the Chronodial, the single-block-tier time machine: no continuous change
 * tracking or branching (see TemporalTimeline) — just one manually-set marker on the block it
 * faces, which can later be restored. Deliberately self-contained; unlike the Time Machine it
 * never touches TemporalWorldData/TemporalTimeline.
 */
@SuppressWarnings("null")
public class ChronodialBlockEntity extends BlockEntity {
    private static final int ENERGY_CAPACITY = 10_000;
    private static final int ENERGY_TRANSFER  = 500;
    private static final long UNSET_TIME = -1L;

    public enum JumpResult { SUCCESS, ALREADY_AT_MARKER, NO_MARKER, INSUFFICIENT_ENERGY }

    private long markerGameTime = UNSET_TIME;
    @Nullable private BlockState markerState;
    @Nullable private CompoundTag markerBlockEntityTag;

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
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
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
    }

    /** Restores the target block to its marked state, paying the jump's energy cost first. */
    public JumpResult jumpToMarker() {
        if (level == null || level.isClientSide || markerState == null) return JumpResult.NO_MARKER;

        BlockPos targetPos = getTargetPos();
        if (level.getBlockState(targetPos).equals(markerState)) return JumpResult.ALREADY_AT_MARKER;

        long cost = ItemEnergyCosts.getCost(markerState.getBlock()).orElse(0);
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
        return JumpResult.SUCCESS;
    }

    // -------------------------------------------------------------------------
    // NBT

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
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
        markerGameTime = tag.contains("MarkerGameTime") ? tag.getLong("MarkerGameTime") : UNSET_TIME;
        markerState = tag.contains("MarkerState")
                ? BlockState.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("MarkerState")).result().orElse(Blocks.AIR.defaultBlockState())
                : null;
        markerBlockEntityTag = tag.contains("MarkerBE") ? tag.getCompound("MarkerBE").copy() : null;
    }
}
