package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
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

/** Container menu for the Chronosphere GUI; exposes the block entity's energy/time state to
 * the client via {@link ContainerData}. Chunk-set state is synced separately (see
 * ChronosphereStateSyncPacket) since it's a variable-size set rather than a fixed set of ints. */
@SuppressWarnings("null")
public class ChronosphereMenu extends AbstractContainerMenu implements TimelineViewMenu {
    private final ChronosphereBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public ChronosphereMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, dataBuffer.readBlockPos());
    }

    public ChronosphereMenu(int id, Inventory playerInventory, BlockPos blockPos) {
        this(id, playerInventory, getBlockEntity(playerInventory, blockPos), ContainerLevelAccess.create(playerInventory.player.level(), blockPos), new SimpleContainerData(12));
    }

    public ChronosphereMenu(int id, Inventory playerInventory, ChronosphereBlockEntity blockEntity, ContainerLevelAccess access, ContainerData data) {
        super(Registration.CHRONOSPHERE_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = access;
        this.data = data;

        checkContainerDataCount(data, 12);
        addDataSlots(data);
    }

    private static ChronosphereBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof ChronosphereBlockEntity) {
            return (ChronosphereBlockEntity) playerInventory.player.level().getBlockEntity(blockPos);
        }

        throw new IllegalStateException("Expected ChronosphereBlockEntity at " + blockPos);
    }

    @Override
    public ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return stillValid(access, player, Registration.CHRONOSPHERE_BLOCK.get());
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

    public ChronosphereBlockEntity getBlockEntity() {
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
