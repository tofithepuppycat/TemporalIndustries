package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.item.DualEntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.item.EntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyCondenserMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static io.github.tofithepuppycat.temporalindustries.block.EntropyCondenser.FACING;

/**
 * Instantly absorbs (powered by FE) any {@link EntropyOrbEntity} orb inside a configurable cuboid
 * range that starts at the block's front face and extends outward, and condenses its value into
 * liquid Order/Chaos fluid across two internal tanks, per IDEAS.md's Entropy Condenser. Also has a
 * single input slot that slowly drains an Order/Chaos Cell into the same tanks, the same unpowered
 * way {@link CrudeEntropyCondenserBlockEntity} works, for topping tanks off by hand.
 */
@SuppressWarnings("null")
public class EntropyCondenserBlockEntity extends BlockEntity implements Container, MenuProvider {
    private static final int ENERGY_CAPACITY = 50_000;
    private static final int ENERGY_MAX_RECEIVE = 500;

    private static final int TANK_CAPACITY = 8_000;
    private static final int MB_PER_UNIT = 50;
    private static final int FE_PER_UNIT = 20;
    private static final int CELL_DRAIN_PER_TICK = 5;
    private static final int SLOT_COUNT = 1;
    public static final int CELL_SLOT = 0;

    public static final int MIN_RANGE = 3;
    public static final int MAX_RANGE = 5;
    private static final int DEFAULT_RANGE = 3;

    private int range = DEFAULT_RANGE;

    private final class CondenserEnergyStorage extends EnergyStorage {
        CondenserEnergyStorage() {
            super(ENERGY_CAPACITY, ENERGY_MAX_RECEIVE, 0);
        }

        @Override public int receiveEnergy(int max, boolean simulate) {
            int v = super.receiveEnergy(max, simulate);
            if (!simulate && v > 0) setChanged();
            return v;
        }

        /** {@link #extractEnergy} is capped at 0 (external capability is receive-only), so
         * condensing needs its own path to spend stored FE that isn't clamped by that cap. */
        boolean consumeInternal(int amount, boolean simulate) {
            if (energy < amount) return false;
            if (!simulate) {
                energy -= amount;
                setChanged();
            }
            return true;
        }
    }

    private final class CondenserFluidHandler implements IFluidHandler {
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
            if (orderTank.isFluidValid(resource)) return orderTank.fill(resource, action);
            if (chaosTank.isFluidValid(resource)) return chaosTank.fill(resource, action);
            return 0;
        }

        @Override public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (!orderTank.getFluid().isEmpty() && orderTank.getFluid().getFluid().isSame(resource.getFluid())) {
                return orderTank.drain(resource, action);
            }
            if (!chaosTank.getFluid().isEmpty() && chaosTank.getFluid().getFluid().isSame(resource.getFluid())) {
                return chaosTank.drain(resource, action);
            }
            return FluidStack.EMPTY;
        }

        @Override public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            if (!orderTank.getFluid().isEmpty()) return orderTank.drain(maxDrain, action);
            if (!chaosTank.getFluid().isEmpty()) return chaosTank.drain(maxDrain, action);
            return FluidStack.EMPTY;
        }
    }

    private final CondenserEnergyStorage energyStorage = new CondenserEnergyStorage();
    private final FluidTank orderTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.ORDER_FLUID.get());
        }
    };
    private final FluidTank chaosTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.CHAOS_FLUID.get());
        }
    };
    private final CondenserFluidHandler fluidHandler = new CondenserFluidHandler();

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final IItemHandler inventory = new InvWrapper(this);

    public EntropyCondenserBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.ENTROPY_CONDENSER_BLOCK_ENTITY.get(), pos, state);
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public IFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public IItemHandler getItemHandler() {
        return inventory;
    }

    public FluidTank getOrderTank() {
        return orderTank;
    }

    public FluidTank getChaosTank() {
        return chaosTank;
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        int clamped = Math.clamp(range, MIN_RANGE, MAX_RANGE);
        if (clamped == this.range) return;
        this.range = clamped;
        setChanged();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, EntropyCondenserBlockEntity be) {
        if (level.isClientSide) return;

        AABB absorbArea = absorbArea(pos, state.getValue(FACING), be.range);
        List<EntropyOrbEntity> orbs = level.getEntitiesOfClass(EntropyOrbEntity.class, absorbArea);
        for (EntropyOrbEntity orb : orbs) {
            be.tryCondense(orb);
        }

        be.drainCell();
    }

    /** The absorb cuboid: rangeXrangeXrange, starting flush against the block's front face
     * (per {@code facing}) and extending outward — not centered on the block itself. */
    private static AABB absorbArea(BlockPos pos, Direction facing, int range) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        double half = range / 2.0;

        double minX, maxX, minY, maxY, minZ, maxZ;
        minY = cy - half;
        maxY = cy + half;

        if (facing.getAxis() == Direction.Axis.Z) {
            minX = cx - half;
            maxX = cx + half;
            if (facing == Direction.SOUTH) {
                minZ = pos.getZ() + 1.0;
                maxZ = minZ + range;
            } else {
                maxZ = pos.getZ();
                minZ = maxZ - range;
            }
        } else {
            minZ = cz - half;
            maxZ = cz + half;
            if (facing == Direction.EAST) {
                minX = pos.getX() + 1.0;
                maxX = minX + range;
            } else {
                maxX = pos.getX();
                minX = maxX - range;
            }
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void tryCondense(EntropyOrbEntity orb) {
        EntropyType type = orb.getEntropyType();
        FluidTank tank = type == EntropyType.ORDER ? orderTank : chaosTank;
        var fluid = type == EntropyType.ORDER ? Registration.ORDER_FLUID.get() : Registration.CHAOS_FLUID.get();

        int mbAmount = orb.getValue() * MB_PER_UNIT;
        int feCost = orb.getValue() * FE_PER_UNIT;

        if (tank.getFluidAmount() + mbAmount > tank.getCapacity()) return;
        if (!energyStorage.consumeInternal(feCost, true)) return;

        tank.fill(new FluidStack(fluid, mbAmount), IFluidHandler.FluidAction.EXECUTE);
        energyStorage.consumeInternal(feCost, false);
        orb.discard();
        setChanged();
    }

    // -------------------------------------------------------------------------
    // Cell slot draining (unpowered; see CrudeEntropyCondenserBlockEntity for the same logic)

    private void drainCell() {
        ItemStack stack = items.get(CELL_SLOT);
        if (stack.isEmpty()) return;

        if (stack.getItem() instanceof EntropyCellItem cellItem) {
            drainSingleCell(stack, cellItem);
        } else if (stack.getItem() instanceof DualEntropyCellItem) {
            drainDualCell(stack);
        }
    }

    private void drainSingleCell(ItemStack stack, EntropyCellItem cellItem) {
        EntropyType type = cellItem.accepts(EntropyType.ORDER) ? EntropyType.ORDER : EntropyType.CHAOS;
        BottleContents contents = EntropyCellItem.getContents(stack);
        if (contents.amount() <= 0) return;

        int drained = drainInto(type, contents.amount());
        if (drained <= 0) return;

        stack.set(Registration.BOTTLE_CONTENTS.get(), new BottleContents(contents.amount() - drained));
        setChanged();
    }

    private void drainDualCell(ItemStack stack) {
        EntropyContents contents = DualEntropyCellItem.getContents(stack);
        int drainedOrder = drainInto(EntropyType.ORDER, contents.order());
        int drainedChaos = drainInto(EntropyType.CHAOS, contents.chaos());
        if (drainedOrder <= 0 && drainedChaos <= 0) return;

        EntropyContents updated = contents
                .with(EntropyType.ORDER, contents.order() - drainedOrder)
                .with(EntropyType.CHAOS, contents.chaos() - drainedChaos);
        stack.set(Registration.ENTROPY_CONTENTS.get(), updated);
        setChanged();
    }

    /** Fills up to {@link #CELL_DRAIN_PER_TICK} of {@code type} into its tank, capped by both the
     * cell's remaining contents and the tank's remaining space. Returns how much was actually drained. */
    private int drainInto(EntropyType type, int available) {
        if (available <= 0) return 0;

        FluidTank tank = type == EntropyType.ORDER ? orderTank : chaosTank;
        var fluid = type == EntropyType.ORDER ? Registration.ORDER_FLUID.get() : Registration.CHAOS_FLUID.get();

        int spaceInTank = tank.getCapacity() - tank.getFluidAmount();
        int amount = Math.min(CELL_DRAIN_PER_TICK, Math.min(available, spaceInTank));
        if (amount <= 0) return 0;

        tank.fill(new FluidStack(fluid, amount), IFluidHandler.FluidAction.EXECUTE);
        return amount;
    }

    // -------------------------------------------------------------------------
    // Container (single cell slot; see ChronoProjectorBlockEntity for why both this and IItemHandler exist)

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.get(CELL_SLOT).isEmpty(); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        setChanged();
    }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.temporalindustries.entropy_condenser");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new EntropyCondenserMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyStorage.serializeNBT(registries));
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        tag.putInt("Range", range);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy")) energyStorage.deserializeNBT(registries, tag.get("Energy"));
        if (tag.contains("OrderTank")) orderTank.readFromNBT(registries, tag.getCompound("OrderTank"));
        if (tag.contains("ChaosTank")) chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        range = tag.contains("Range") ? Math.clamp(tag.getInt("Range"), MIN_RANGE, MAX_RANGE) : DEFAULT_RANGE;
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
