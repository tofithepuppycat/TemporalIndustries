package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyBatteryBlockEntity;
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

/**
 * Container menu for the Entropy Battery GUI: a Cell input slot, an output slot the tanks slowly
 * fill an inserted Cell back out of, and both tanks' fill/capacity.
 */
@SuppressWarnings("null")
public class EntropyBatteryMenu extends AbstractContainerMenu {
    private static final int CELL_SLOT_X = 80;
    private static final int CELL_SLOT_Y = 20;
    private static final int OUTPUT_SLOT_X = 80;
    private static final int OUTPUT_SLOT_Y = 51;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 84;
    private static final int HOTBAR_Y = 142;

    private final EntropyBatteryBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public EntropyBatteryMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public EntropyBatteryMenu(int id, Inventory playerInventory, EntropyBatteryBlockEntity blockEntity) {
        this(id, playerInventory, blockEntity, ContainerLevelAccess.create(playerInventory.player.level(), blockEntity.getBlockPos()), new SimpleContainerData(3));
    }

    public EntropyBatteryMenu(int id, Inventory playerInventory, EntropyBatteryBlockEntity blockEntity, ContainerLevelAccess access, ContainerData data) {
        super(Registration.ENTROPY_BATTERY_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = access;
        this.data = data;

        checkContainerDataCount(data, 3);
        addDataSlots(data);

        addSlot(new Slot(blockEntity, EntropyBatteryBlockEntity.CELL_SLOT, CELL_SLOT_X, CELL_SLOT_Y) {
            @Override public boolean mayPlace(@NotNull ItemStack stack) {
                return stack.getItem() instanceof EntropyReceptacle;
            }
        });
        addSlot(new Slot(blockEntity, EntropyBatteryBlockEntity.OUTPUT_SLOT, OUTPUT_SLOT_X, OUTPUT_SLOT_Y) {
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

    private static EntropyBatteryBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof EntropyBatteryBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected EntropyBatteryBlockEntity at " + blockPos);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        data.set(0, blockEntity.getOrderTank().getFluidAmount());
        data.set(1, blockEntity.getChaosTank().getFluidAmount());
        // Capacity is fixed and identical for both tanks, so one slot covers both.
        data.set(2, blockEntity.getOrderTank().getCapacity());
    }

    public int getOrderFluidAmount() {
        return data.get(0);
    }

    public int getChaosFluidAmount() {
        return data.get(1);
    }

    public int getTankCapacity() {
        return data.get(2);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();
        ItemStack result = stackInSlot.copy();

        int cellSlot = EntropyBatteryBlockEntity.CELL_SLOT;
        int outputSlot = EntropyBatteryBlockEntity.OUTPUT_SLOT;
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
        return stillValid(access, player, Registration.ENTROPY_BATTERY_BLOCK.get());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
