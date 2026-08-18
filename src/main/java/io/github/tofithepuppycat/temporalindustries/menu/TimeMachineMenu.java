package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimeMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
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

/** Container menu for the Time Machine GUI; exposes the block entity's energy/time state to
 * the client via {@link ContainerData}. */
@SuppressWarnings("null")
public class TimeMachineMenu extends AbstractContainerMenu implements TimelineViewMenu {
    private final TimeMachineBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public TimeMachineMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, dataBuffer.readBlockPos());
    }

    public TimeMachineMenu(int id, Inventory playerInventory, BlockPos blockPos) {
        this(id, playerInventory, getBlockEntity(playerInventory, blockPos), ContainerLevelAccess.create(playerInventory.player.level(), blockPos), new SimpleContainerData(13));
    }

    public TimeMachineMenu(int id, Inventory playerInventory, TimeMachineBlockEntity blockEntity, ContainerLevelAccess access, ContainerData data) {
        super(Registration.TIME_MACHINE_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = access;
        this.data = data;

        checkContainerDataCount(data, 13);
        addDataSlots(data);
    }

    private static TimeMachineBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof TimeMachineBlockEntity) {
            return (TimeMachineBlockEntity) playerInventory.player.level().getBlockEntity(blockPos);
        }

        throw new IllegalStateException("Expected TimeMachineBlockEntity at " + blockPos);
    }

    @Override
    public ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return stillValid(access, player, Registration.TIME_MACHINE_BLOCK.get());
    }

    public int getEnergyStored() {
        return (data.get(1) << 16) | (data.get(0) & 0xFFFF);
    }

    public int getEnergyCapacity() {
        return (data.get(3) << 16) | (data.get(2) & 0xFFFF);
    }

    private long getLongFromData(int startIndex) {
        long b0 = data.get(startIndex) & 0xFFFFL;
        long b1 = data.get(startIndex + 1) & 0xFFFFL;
        long b2 = data.get(startIndex + 2) & 0xFFFFL;
        long b3 = data.get(startIndex + 3) & 0xFFFFL;
        return b0 | (b1 << 16) | (b2 << 32) | (b3 << 48);
    }

    public long getPlacedGameTime() {
        return getLongFromData(4);
    }

    public long getSelectedGameTime() {
        return getLongFromData(8);
    }

    public int getEntropy() {
        return data.get(12);
    }

    public int getEntropyMax() {
        return AbstractTimelineMachineBlockEntity.ENTROPY_MAX;
    }

    public TimeMachineBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public BlockPos getBlockPos() {
        return blockPos;
    }

    @Override
    public TimelineViewProvider getTimelineProvider() {
        return blockEntity;
    }
}
