package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.DespawnAcceleratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;

public class DespawnAcceleratorMenu extends AbstractAcceleratorMenu {
    public DespawnAcceleratorMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public DespawnAcceleratorMenu(int id, Inventory playerInventory, DespawnAcceleratorBlockEntity blockEntity) {
        super(Registration.DESPAWN_ACCELERATOR_MENU.get(), id, playerInventory, blockEntity);
    }

    private static DespawnAcceleratorBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof DespawnAcceleratorBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected DespawnAcceleratorBlockEntity at " + blockPos);
    }

    @Override
    protected boolean isValidInput(@NotNull ItemStack stack) {
        return !stack.isEmpty();
    }

    @Override
    protected Block validityBlock() {
        return Registration.DESPAWN_ACCELERATOR_BLOCK.get();
    }
}
