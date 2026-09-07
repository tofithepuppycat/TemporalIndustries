package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.DespawnAcceleratorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * Order variant of {@link DecayAcceleratorBlockEntity}: accepts any item/block, not just
 * compostables, and destroys it for a flat ORDER payout.
 */
public class DespawnAcceleratorBlockEntity extends AbstractAcceleratorBlockEntity {
    private static final int ORDER_PER_ITEM = 1;

    public DespawnAcceleratorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.DESPAWN_ACCELERATOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected boolean isValidInput(ItemStack stack) {
        return !stack.isEmpty();
    }

    @Override
    protected int entropyValue(ItemStack stack) {
        return ORDER_PER_ITEM;
    }

    @Override
    protected EntropyType entropyType() {
        return EntropyType.ORDER;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.temporalindustries.despawn_accelerator");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new DespawnAcceleratorMenu(id, inventory, this);
    }
}
