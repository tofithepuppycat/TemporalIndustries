package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyCondenserBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/** Container menu for the Entropy Condenser GUI: energy stored/capacity plus both tanks'
 * fill/capacity, packed into {@link ContainerData} 16-bit slots the same way as {@link ChronosphereMenu},
 * plus a Cell input slot (see {@link CrudeEntropyCondenserMenu} for its lower tier's identical slot). */
@SuppressWarnings("null")
public class EntropyCondenserMenu extends AbstractContainerMenu {
    private static final int CELL_SLOT_X = 140;
    private static final int CELL_SLOT_Y = 20;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 140;
    private static final int HOTBAR_Y = 202;

    private final EntropyCondenserBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public EntropyCondenserMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public EntropyCondenserMenu(int id, Inventory playerInventory, EntropyCondenserBlockEntity blockEntity) {
        this(id, playerInventory, blockEntity, ContainerLevelAccess.create(playerInventory.player.level(), blockEntity.getBlockPos()), new SimpleContainerData(8));
    }

    public EntropyCondenserMenu(int id, Inventory playerInventory, EntropyCondenserBlockEntity blockEntity, ContainerLevelAccess access, ContainerData data) {
        super(Registration.ENTROPY_CONDENSER_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = access;
        this.data = data;

        checkContainerDataCount(data, 8);
        addDataSlots(data);

        addSlot(new Slot(blockEntity, EntropyCondenserBlockEntity.CELL_SLOT, CELL_SLOT_X, CELL_SLOT_Y) {
            @Override public boolean mayPlace(@NotNull ItemStack stack) {
                return stack.getItem() instanceof EntropyReceptacle;
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        }
    }

    private static EntropyCondenserBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof EntropyCondenserBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected EntropyCondenserBlockEntity at " + blockPos);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        setIntPacked(0, blockEntity.getEnergyStorage().getEnergyStored());
        setIntPacked(2, blockEntity.getEnergyStorage().getMaxEnergyStored());
        data.set(4, blockEntity.getOrderTank().getFluidAmount());
        data.set(5, blockEntity.getChaosTank().getFluidAmount());
        // Capacity is fixed and identical for both tanks, so one slot covers both.
        data.set(6, blockEntity.getOrderTank().getCapacity());
        data.set(7, blockEntity.getRange());
    }

    private void setIntPacked(int lowIndex, int value) {
        data.set(lowIndex, value & 0xFFFF);
        data.set(lowIndex + 1, (value >>> 16) & 0xFFFF);
    }

    public int getEnergyStored() {
        return (data.get(1) << 16) | (data.get(0) & 0xFFFF);
    }

    public int getEnergyCapacity() {
        return (data.get(3) << 16) | (data.get(2) & 0xFFFF);
    }

    public int getOrderFluidAmount() {
        return data.get(4);
    }

    public int getChaosFluidAmount() {
        return data.get(5);
    }

    public int getTankCapacity() {
        return data.get(6);
    }

    public int getRange() {
        return data.get(7);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();
        ItemStack result = stackInSlot.copy();

        int cellSlot = EntropyCondenserBlockEntity.CELL_SLOT;
        int inventoryStart = cellSlot + 1;
        int inventoryEnd = inventoryStart + 36;

        if (index == cellSlot) {
            if (!moveItemStackTo(stackInSlot, inventoryStart, inventoryEnd, true)) return ItemStack.EMPTY;
        } else {
            if (!(stackInSlot.getItem() instanceof EntropyReceptacle) || !moveItemStackTo(stackInSlot, cellSlot, cellSlot + 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return stillValid(access, player, Registration.ENTROPY_CONDENSER_BLOCK.get());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
