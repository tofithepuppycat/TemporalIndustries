package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropicBatteryBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
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

/**
 * Container menu for the Entropic Battery GUI: a Cell input slot, an output slot the tanks slowly
 * fill an inserted Cell back out of, and both tanks' fill/capacity.
 */
@SuppressWarnings("null")
public class EntropicBatteryMenu extends AbstractContainerMenu {
    private static final int CELL_SLOT_X = 80;
    private static final int CELL_SLOT_Y = 20;
    private static final int OUTPUT_SLOT_X = 80;
    private static final int OUTPUT_SLOT_Y = 51;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 84;
    private static final int HOTBAR_Y = 142;

    /** Container data is synced as 16-bit values, so each int is sent as a low and a high half. */
    private static final int DATA_COUNT = 11;

    private final EntropicBatteryBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public EntropicBatteryMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public EntropicBatteryMenu(int id, Inventory playerInventory, EntropicBatteryBlockEntity blockEntity) {
        this(id, playerInventory, blockEntity, ContainerLevelAccess.create(playerInventory.player.level(), blockEntity.getBlockPos()), new SimpleContainerData(DATA_COUNT));
    }

    public EntropicBatteryMenu(int id, Inventory playerInventory, EntropicBatteryBlockEntity blockEntity, ContainerLevelAccess access, ContainerData data) {
        super(Registration.ENTROPIC_BATTERY_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = access;
        this.data = data;

        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);

        addSlot(new Slot(blockEntity, EntropicBatteryBlockEntity.CELL_SLOT, CELL_SLOT_X, CELL_SLOT_Y) {
            @Override public boolean mayPlace(@NotNull ItemStack stack) {
                return stack.getItem() instanceof EntropyReceptacle;
            }
        });
        addSlot(new Slot(blockEntity, EntropicBatteryBlockEntity.OUTPUT_SLOT, OUTPUT_SLOT_X, OUTPUT_SLOT_Y) {
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

    private static EntropicBatteryBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof EntropicBatteryBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected EntropicBatteryBlockEntity at " + blockPos);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        setInt(0, blockEntity.groupAmount(EntropyType.ORDER));
        setInt(2, blockEntity.groupAmount(EntropyType.CHAOS));
        setInt(4, blockEntity.groupCapacity());
        data.set(6, blockEntity.getGroupSize());
        setInt(7, blockEntity.groupEnergy());
        setInt(9, blockEntity.groupEnergyCapacity());
    }

    private void setInt(int slot, int value) {
        data.set(slot, value & 0xFFFF);
        data.set(slot + 1, value >>> 16);
    }

    private int getInt(int slot) {
        return (data.get(slot) & 0xFFFF) | ((data.get(slot + 1) & 0xFFFF) << 16);
    }

    public int getOrderFluidAmount() {
        return getInt(0);
    }

    public int getChaosFluidAmount() {
        return getInt(2);
    }

    /** Capacity of one tank across the whole multiblock battery. */
    public int getTankCapacity() {
        return getInt(4);
    }

    public int getEnergyStored() {
        return getInt(7);
    }

    public int getEnergyCapacity() {
        return getInt(9);
    }

    public int getBatteryCount() {
        return data.get(6);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();
        ItemStack result = stackInSlot.copy();

        int cellSlot = EntropicBatteryBlockEntity.CELL_SLOT;
        int outputSlot = EntropicBatteryBlockEntity.OUTPUT_SLOT;
        int inventoryStart = outputSlot + 1;
        int inventoryEnd = inventoryStart + 36;

        if (index == cellSlot || index == outputSlot) {
            if (!moveItemStackTo(stackInSlot, inventoryStart, inventoryEnd, true)) return ItemStack.EMPTY;
        } else {
            if (!(stackInSlot.getItem() instanceof EntropyReceptacle) || !moveItemStackTo(stackInSlot, cellSlot, outputSlot + 1, false)) {
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
        return stillValid(access, player, Registration.ENTROPIC_BATTERY_BLOCK.get());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
