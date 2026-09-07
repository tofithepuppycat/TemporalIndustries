package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractAcceleratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;

/**
 * Shared menu skeleton for the Decay/Despawn Accelerator GUIs: one input slot (filtered by
 * subclass), a synced progress value, and the player inventory.
 */
@SuppressWarnings("null")
public abstract class AbstractAcceleratorMenu extends AbstractContainerMenu {
    private static final int INPUT_SLOT_X = 80;
    private static final int INPUT_SLOT_Y = 34;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 84;
    private static final int HOTBAR_Y = 142;

    private final AbstractAcceleratorBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    protected AbstractAcceleratorMenu(MenuType<?> type, int id, Inventory playerInventory, AbstractAcceleratorBlockEntity blockEntity) {
        this(type, id, playerInventory, blockEntity, new SimpleContainerData(1));
    }

    protected AbstractAcceleratorMenu(MenuType<?> type, int id, Inventory playerInventory, AbstractAcceleratorBlockEntity blockEntity, ContainerData data) {
        super(type, id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = ContainerLevelAccess.create(playerInventory.player.level(), blockPos);
        this.data = data;

        checkContainerDataCount(data, 1);
        addDataSlots(data);

        addSlot(new Slot(blockEntity, AbstractAcceleratorBlockEntity.INPUT_SLOT, INPUT_SLOT_X, INPUT_SLOT_Y) {
            @Override public boolean mayPlace(@NotNull ItemStack stack) {
                return isValidInput(stack);
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

    protected abstract boolean isValidInput(ItemStack stack);

    protected abstract Block validityBlock();

    @Override
    public boolean stillValid(@NotNull Player player) {
        return stillValid(access, player, validityBlock());
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        data.set(0, blockEntity.getProgress());
    }

    public int getProgress() {
        return data.get(0);
    }

    public static int maxProgress() {
        return AbstractAcceleratorBlockEntity.PROCESS_TIME_TICKS;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();
        ItemStack result = stackInSlot.copy();

        int inputSlot = AbstractAcceleratorBlockEntity.INPUT_SLOT;
        int inventoryStart = inputSlot + 1;
        int inventoryEnd = inventoryStart + 36;

        if (index == inputSlot) {
            if (!moveItemStackTo(stackInSlot, inventoryStart, inventoryEnd, true)) return ItemStack.EMPTY;
        } else {
            if (!isValidInput(stackInSlot) || !moveItemStackTo(stackInSlot, inputSlot, inputSlot + 1, false)) {
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

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
