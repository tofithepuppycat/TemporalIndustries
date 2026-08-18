package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.CrudeEntropyCondenserBlockEntity;
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

/** Container menu for the Crude Entropy Condenser GUI: one Order/Chaos Cell slot plus both tanks'
 * fill/capacity, packed into {@link ContainerData} the same way as {@link EntropyCondenserMenu}. */
@SuppressWarnings("null")
public class CrudeEntropyCondenserMenu extends AbstractContainerMenu {
    private static final int CELL_SLOT_X = 80;
    private static final int CELL_SLOT_Y = 20;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 84;
    private static final int HOTBAR_Y = 142;

    private final CrudeEntropyCondenserBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public CrudeEntropyCondenserMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public CrudeEntropyCondenserMenu(int id, Inventory playerInventory, CrudeEntropyCondenserBlockEntity blockEntity) {
        this(id, playerInventory, blockEntity, ContainerLevelAccess.create(playerInventory.player.level(), blockEntity.getBlockPos()), new SimpleContainerData(3));
    }

    public CrudeEntropyCondenserMenu(int id, Inventory playerInventory, CrudeEntropyCondenserBlockEntity blockEntity, ContainerLevelAccess access, ContainerData data) {
        super(Registration.CRUDE_ENTROPY_CONDENSER_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = access;
        this.data = data;

        checkContainerDataCount(data, 3);
        addDataSlots(data);

        addSlot(new Slot(blockEntity, CrudeEntropyCondenserBlockEntity.CELL_SLOT, CELL_SLOT_X, CELL_SLOT_Y) {
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

    private static CrudeEntropyCondenserBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof CrudeEntropyCondenserBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected CrudeEntropyCondenserBlockEntity at " + blockPos);
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

        int cellSlot = CrudeEntropyCondenserBlockEntity.CELL_SLOT;
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
        return stillValid(access, player, Registration.CRUDE_ENTROPY_CONDENSER_BLOCK.get());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
