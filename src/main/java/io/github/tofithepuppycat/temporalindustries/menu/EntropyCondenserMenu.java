package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyCondenserBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/** Container menu for the Entropy Condenser GUI: energy stored/capacity plus both tanks'
 * fill/capacity, packed into {@link ContainerData} 16-bit slots the same way as {@link ChronosphereMenu}. */
@SuppressWarnings("null")
public class EntropyCondenserMenu extends AbstractContainerMenu {
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

    @Override
    public ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return stillValid(access, player, Registration.ENTROPY_CONDENSER_BLOCK.get());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
