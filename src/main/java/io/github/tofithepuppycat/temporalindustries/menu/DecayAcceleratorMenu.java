package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.DecayAcceleratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComposterBlock;
import org.jetbrains.annotations.NotNull;

public class DecayAcceleratorMenu extends AbstractAcceleratorMenu {
    public DecayAcceleratorMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf dataBuffer) {
        this(id, playerInventory, getBlockEntity(playerInventory, dataBuffer.readBlockPos()));
    }

    public DecayAcceleratorMenu(int id, Inventory playerInventory, DecayAcceleratorBlockEntity blockEntity) {
        super(Registration.DECAY_ACCELERATOR_MENU.get(), id, playerInventory, blockEntity);
    }

    private static DecayAcceleratorBlockEntity getBlockEntity(Inventory playerInventory, BlockPos blockPos) {
        if (playerInventory.player.level().getBlockEntity(blockPos) instanceof DecayAcceleratorBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("Expected DecayAcceleratorBlockEntity at " + blockPos);
    }

    @Override
    protected boolean isValidInput(@NotNull ItemStack stack) {
        return ComposterBlock.getValue(stack) >= 0F;
    }

    @Override
    protected Block validityBlock() {
        return Registration.DECAY_ACCELERATOR_BLOCK.get();
    }
}
