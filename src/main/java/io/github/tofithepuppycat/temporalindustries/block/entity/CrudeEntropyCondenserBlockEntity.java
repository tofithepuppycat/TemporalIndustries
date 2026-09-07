package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.CrudeEntropyCondenserMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Lower tier of {@link io.github.tofithepuppycat.temporalindustries.block.EntropyCondenser}: drains
 * liquid Order/Chaos out of a cell in its single input slot into its own smaller tanks, no power involved.
 */
@SuppressWarnings("null")
public class CrudeEntropyCondenserBlockEntity extends BlockEntity implements Container, MenuProvider, EntropyInfoProvider {
    public static final int TANK_CAPACITY = 4_000;
    private static final int DRAIN_PER_TICK = 25; // mB pulled out of the slotted cell per tick
    private static final int SLOT_COUNT = 1;
    public static final int CELL_SLOT = 0;

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

    public CrudeEntropyCondenserBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.CRUDE_ENTROPY_CONDENSER_BLOCK_ENTITY.get(), pos, state);
    }

    public IItemHandler getItemHandler() {
        return inventory;
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

    @Override
    public List<Component> getEntropyTooltip() {
        return List.of(
                getDisplayName().copy().withStyle(ChatFormatting.WHITE),
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(orderTank.getFluidAmount()), EntropyDisplay.formatFluid(orderTank.getCapacity()))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.ORDER)),
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(chaosTank.getFluidAmount()), EntropyDisplay.formatFluid(chaosTank.getCapacity()))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.CHAOS)));
    }

    public static void tick(Level level, BlockPos pos, BlockState state, CrudeEntropyCondenserBlockEntity be) {
        if (level.isClientSide) return;
        be.drainCell();
    }

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

    /** Moves up to {@link #DRAIN_PER_TICK} mB of {@code type} out of the cell and into its tank,
     * capped by both the cell's contents and the tank's remaining space. Returns how much moved. */
    private int drainInto(EntropyReceptacle receptacle, ItemStack stack, EntropyType type) {
        FluidTank tank = type == EntropyType.ORDER ? orderTank : chaosTank;
        int spaceInTank = tank.getCapacity() - tank.getFluidAmount();
        int amount = Math.min(DRAIN_PER_TICK, Math.min(receptacle.amount(stack, type), spaceInTank));
        if (amount <= 0) return 0;

        int drained = receptacle.drain(stack, type, amount);
        if (drained <= 0) return 0;

        tank.fill(EntropyFluids.stack(type, drained), IFluidHandler.FluidAction.EXECUTE);
        return drained;
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
        return Component.translatable("block.temporalindustries.crude_entropy_condenser");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new CrudeEntropyCondenserMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("OrderTank")) orderTank.readFromNBT(registries, tag.getCompound("OrderTank"));
        if (tag.contains("ChaosTank")) chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
