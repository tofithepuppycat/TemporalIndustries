package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.WithergenicReactorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Container menu for the Withergenic Reactor GUI: no slots of its own, just the synced internal
 * balance, efficiency, output hatch fill and status shown by the screen.
 */
@SuppressWarnings("null")
public class WithergenicReactorMenu extends AbstractContainerMenu {
    private static final int DATA_COUNT = 5;
    private static final int INVENTORY_X = 8;
    private static final int INVENTORY_Y = 84;
    private static final int HOTBAR_Y = 142;

    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public WithergenicReactorMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, dataBuffer.readBlockPos(), new SimpleContainerData(DATA_COUNT));
    }

    public WithergenicReactorMenu(int id, Inventory playerInventory, WithergenicReactorBlockEntity blockEntity, ContainerData data) {
        this(id, playerInventory, blockEntity.getBlockPos(), data);
    }

    private WithergenicReactorMenu(int id, Inventory playerInventory, BlockPos blockPos, ContainerData data) {
        super(Registration.WITHERGENIC_REACTOR_MENU.get(), id);
        this.blockPos = blockPos;
        this.access = ContainerLevelAccess.create(playerInventory.player.level(), blockPos);
        this.data = data;

        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        }
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    /** Internal Chaos amount out of {@link WithergenicReactorBlockEntity#ENTROPY_MAX}. */
    public int getChaosAmount() {
        return data.get(0);
    }

    /** -1 (full Order) .. +1 (full Chaos). */
    public float getBalance() {
        int half = WithergenicReactorBlockEntity.ENTROPY_MAX / 2;
        return (getChaosAmount() - half) / (float) half;
    }

    public int getEfficiencyPercent() {
        return data.get(1);
    }

    public int getBufferAmount() {
        return data.get(2);
    }

    public int getStatus() {
        return data.get(3);
    }

    public int getOutputPerTick() {
        return data.get(4);
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return stillValid(access, player, Registration.WITHERGENIC_REACTOR_BLOCK.get());
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
