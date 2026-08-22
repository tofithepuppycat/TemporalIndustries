package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyCondenserMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
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
 * way {@link CrudeEntropyCondenserBlockEntity} works, for topping tanks off by hand, and an output
 * slot that slowly fills any {@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle}
 * placed in it back out of the tanks.
 */
@SuppressWarnings("null")
public class EntropyCondenserBlockEntity extends BlockEntity implements Container, MenuProvider, EntropyInfoProvider {
    private static final int ENERGY_CAPACITY = 50_000;
    private static final int ENERGY_MAX_RECEIVE = 500;

    private static final int TANK_CAPACITY = 8_000;
    private static final int FE_PER_UNIT = 20;
    private static final int CELL_DRAIN_PER_TICK = 50; // mB moved between a slotted cell and the tanks per tick
    private static final int OUTPUT_FILL_PER_TICK = 50;
    private static final int SLOT_COUNT = 2;
    public static final int CELL_SLOT = 0;
    public static final int OUTPUT_SLOT = 1;

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

    @Override
    public List<Component> getEntropyTooltip() {
        return List.of(
                getDisplayName().copy().withStyle(ChatFormatting.WHITE),
                Component.translatable("overlay.temporalindustries.entropy_glasses.order",
                        orderTank.getFluidAmount(), orderTank.getCapacity()).withStyle(ChatFormatting.GRAY),
                Component.translatable("overlay.temporalindustries.entropy_glasses.chaos",
                        chaosTank.getFluidAmount(), chaosTank.getCapacity()).withStyle(ChatFormatting.DARK_PURPLE));
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
        be.fillOutput();
    }

    /** The absorb cuboid: rangeXrangeXrange, starting flush against the block's front face
     * (per {@code facing}) and extending outward — not centered on the block itself. Public so the
     * client can render its perimeter (see EntropyCondenserRangeRenderer) from the same geometry
     * the server uses to catch orbs. */
    public static AABB absorbArea(BlockPos pos, Direction facing, int range) {
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

        int mbAmount = EntropyFluids.toMillibuckets(orb.getValue());
        int feCost = orb.getValue() * FE_PER_UNIT;

        if (tank.getFluidAmount() + mbAmount > tank.getCapacity()) return;
        if (!energyStorage.consumeInternal(feCost, true)) return;

        tank.fill(new FluidStack(fluid, mbAmount), IFluidHandler.FluidAction.EXECUTE);
        energyStorage.consumeInternal(feCost, false);
        orb.discard();
        setChanged();
        syncToClients();
    }

    // -------------------------------------------------------------------------
    // Cell slot draining (unpowered; see CrudeEntropyCondenserBlockEntity for the same logic)

    private void drainCell() {
        ItemStack stack = items.get(CELL_SLOT);
        if (!(stack.getItem() instanceof EntropyReceptacle receptacle)) return;

        int drained = 0;
        for (EntropyType type : EntropyType.values()) {
            drained += drainInto(receptacle, stack, type);
        }
        if (drained <= 0) return;

        setChanged();
        syncToClients();
    }

    /** Moves up to {@link #CELL_DRAIN_PER_TICK} mB of {@code type} out of the cell and into its tank,
     * capped by both the cell's contents and the tank's remaining space. Returns how much moved. */
    private int drainInto(EntropyReceptacle receptacle, ItemStack stack, EntropyType type) {
        FluidTank tank = type == EntropyType.ORDER ? orderTank : chaosTank;
        int spaceInTank = tank.getCapacity() - tank.getFluidAmount();
        int amount = Math.min(CELL_DRAIN_PER_TICK, Math.min(receptacle.amount(stack, type), spaceInTank));
        if (amount <= 0) return 0;

        int drained = receptacle.drain(stack, type, amount);
        if (drained <= 0) return 0;

        tank.fill(EntropyFluids.stack(type, drained), IFluidHandler.FluidAction.EXECUTE);
        return drained;
    }

    // -------------------------------------------------------------------------
    // Output slot filling (unpowered; reverse of the cell slot draining above)

    /** Tries to push Order then Chaos out of the tanks into whatever {@link EntropyReceptacle} sits
     * in {@link #OUTPUT_SLOT}, up to {@link #OUTPUT_FILL_PER_TICK} of each per tick. */
    private void fillOutput() {
        ItemStack stack = items.get(OUTPUT_SLOT);
        if (!(stack.getItem() instanceof EntropyReceptacle receptacle)) return;

        int filledOrder = fillFrom(orderTank, EntropyType.ORDER, stack, receptacle);
        int filledChaos = fillFrom(chaosTank, EntropyType.CHAOS, stack, receptacle);
        if (filledOrder <= 0 && filledChaos <= 0) return;

        setChanged();
        syncToClients();
    }

    /** Drains up to {@link #OUTPUT_FILL_PER_TICK} mB from {@code tank} into whatever the receptacle
     * accepts. Returns how much was actually accepted. */
    private int fillFrom(FluidTank tank, EntropyType type, ItemStack stack, EntropyReceptacle receptacle) {
        int available = Math.min(OUTPUT_FILL_PER_TICK, tank.getFluidAmount());
        if (available <= 0) return 0;

        int accepted = receptacle.fill(stack, type, available);
        if (accepted <= 0) return 0;

        tank.drain(accepted, IFluidHandler.FluidAction.EXECUTE);
        return accepted;
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
    // Container (cell input + output slots; see ChronoProjectorBlockEntity for why both this and IItemHandler exist)

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.get(CELL_SLOT).isEmpty() && items.get(OUTPUT_SLOT).isEmpty(); }
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
