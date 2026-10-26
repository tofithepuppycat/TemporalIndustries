package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyBalancerBlockEntity;
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

/** Container menu for the Entropy Balancer GUI: both buffers, the target and the state of the bound output. */
@SuppressWarnings("null")
public class EntropyBalancerMenu extends AbstractContainerMenu {
    private static final int DATA_COUNT = 12;

    private final EntropyBalancerBlockEntity blockEntity;
    private final BlockPos blockPos;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    public EntropyBalancerMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public EntropyBalancerMenu(int id, Inventory playerInventory, EntropyBalancerBlockEntity blockEntity) {
        super(Registration.ENTROPY_BALANCER_MENU.get(), id);
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getBlockPos();
        this.access = ContainerLevelAccess.create(playerInventory.player.level(), blockPos);
        this.data = new SimpleContainerData(DATA_COUNT);
        addDataSlots(data);
    }

    private static EntropyBalancerBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof EntropyBalancerBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected EntropyBalancerBlockEntity at " + blockPos);
    }

    @Override
    public void broadcastChanges() {
        data.set(0, blockEntity.getOrderTank().getFluidAmount());
        data.set(1, blockEntity.getChaosTank().getFluidAmount());
        data.set(2, EntropyBalancerBlockEntity.TANK_CAPACITY);
        data.set(3, blockEntity.getTarget());
        data.set(4, blockEntity.getStatus().ordinal());
        data.set(5, blockEntity.getOutputOffset());
        BlockPos output = blockEntity.getOutputPos();
        setIntPacked(6, output == null ? 0 : output.getX());
        setIntPacked(8, output == null ? 0 : output.getY());
        setIntPacked(10, output == null ? 0 : output.getZ());
        super.broadcastChanges();
    }

    private void setIntPacked(int lowIndex, int value) {
        data.set(lowIndex, value & 0xFFFF);
        data.set(lowIndex + 1, (value >>> 16) & 0xFFFF);
    }

    private int getIntPacked(int lowIndex) {
        return (data.get(lowIndex + 1) << 16) | (data.get(lowIndex) & 0xFFFF);
    }

    public int getOrderFluidAmount() { return data.get(0); }
    public int getChaosFluidAmount() { return data.get(1); }
    public int getTankCapacity() { return data.get(2); }
    public int getTarget() { return data.get(3); }

    public EntropyBalancerBlockEntity.OutputStatus getStatus() {
        return EntropyBalancerBlockEntity.OutputStatus.values()[Math.clamp(data.get(4), 0,
                EntropyBalancerBlockEntity.OutputStatus.values().length - 1)];
    }

    /** The output's offset from balance on the same scale as {@link #getTarget()}. */
    public int getOutputOffset() { return data.get(5); }

    public BlockPos getOutputPos() {
        return new BlockPos(getIntPacked(6), getIntPacked(8), getIntPacked(10));
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return stillValid(access, player, Registration.ENTROPY_BALANCER_BLOCK.get());
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}
