package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyChangeInducerBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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

/** Container menu for the Entropy Change Inducer GUI: an input/output slot pair plus both entropy
 * tanks' fill/capacity, the liquid material tank's fill/capacity/fluid, and processing progress,
 * packed into {@link ContainerData} the same way as {@link EntropyCondenserMenu}. */
@SuppressWarnings("null")
public class EntropyChangeInducerMenu extends AbstractContainerMenu {
    private static final int INPUT_SLOT_X = 55;
    private static final int INPUT_SLOT_Y = 35;
    private static final int OUTPUT_SLOT_X = 116;
    private static final int OUTPUT_SLOT_Y = 35;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 84;
    private static final int HOTBAR_Y = 142;

    private final EntropyChangeInducerBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public EntropyChangeInducerMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public EntropyChangeInducerMenu(int id, Inventory playerInventory, EntropyChangeInducerBlockEntity blockEntity) {
        this(id, playerInventory, blockEntity, ContainerLevelAccess.create(playerInventory.player.level(), blockEntity.getBlockPos()), new SimpleContainerData(8));
    }

    public EntropyChangeInducerMenu(int id, Inventory playerInventory, EntropyChangeInducerBlockEntity blockEntity, ContainerLevelAccess access, ContainerData data) {
        super(Registration.ENTROPY_CHANGE_INDUCER_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = access;
        this.data = data;

        checkContainerDataCount(data, 8);
        addDataSlots(data);

        addSlot(new Slot(blockEntity, EntropyChangeInducerBlockEntity.INPUT_SLOT, INPUT_SLOT_X, INPUT_SLOT_Y));
        addSlot(new Slot(blockEntity, EntropyChangeInducerBlockEntity.OUTPUT_SLOT, OUTPUT_SLOT_X, OUTPUT_SLOT_Y) {
            @Override public boolean mayPlace(@NotNull ItemStack stack) {
                return false;
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

    private static EntropyChangeInducerBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof EntropyChangeInducerBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected EntropyChangeInducerBlockEntity at " + blockPos);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        data.set(0, blockEntity.getChaosTank().getFluidAmount());
        data.set(1, blockEntity.getOrderTank().getFluidAmount());
        // Capacity is fixed and identical for both entropy tanks, so one slot covers both.
        data.set(2, blockEntity.getChaosTank().getCapacity());
        data.set(3, blockEntity.getLiquidTank().getFluidAmount());
        data.set(4, blockEntity.getLiquidTank().getCapacity());
        data.set(5, BuiltInRegistries.FLUID.getId(blockEntity.getLiquidTank().getFluid().getFluid()));
        data.set(6, blockEntity.getProgress());
        EntropyType activeType = blockEntity.getActiveType();
        data.set(7, activeType == null ? 0 : activeType.ordinal() + 1);
    }

    public int getChaosFluidAmount() {
        return data.get(0);
    }

    public int getOrderFluidAmount() {
        return data.get(1);
    }

    public int getEntropyTankCapacity() {
        return data.get(2);
    }

    public int getLiquidFluidAmount() {
        return data.get(3);
    }

    public int getLiquidTankCapacity() {
        return data.get(4);
    }

    public int getLiquidFluidId() {
        return data.get(5);
    }

    public int getProgress() {
        return data.get(6);
    }

    /** 0 = idle, 1 = Order, 2 = Chaos (see {@link EntropyType} declaration order). */
    public int getActiveTypeCode() {
        return data.get(7);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();
        ItemStack result = stackInSlot.copy();

        int inputSlot = EntropyChangeInducerBlockEntity.INPUT_SLOT;
        int outputSlot = EntropyChangeInducerBlockEntity.OUTPUT_SLOT;
        int inventoryStart = outputSlot + 1;
        int inventoryEnd = inventoryStart + 36;

        if (index == inputSlot || index == outputSlot) {
            if (!moveItemStackTo(stackInSlot, inventoryStart, inventoryEnd, true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stackInSlot, inputSlot, inputSlot + 1, false)) return ItemStack.EMPTY;
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
        return stillValid(access, player, Registration.ENTROPY_CHANGE_INDUCER_BLOCK.get());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
