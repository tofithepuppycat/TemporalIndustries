package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.DecayAcceleratorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * Early/mid game CHAOS generator: feed it compostable items and it rots them down, spawning a
 * CHAOS orb sized by how compostable the item is ({@link ComposterBlock#getValue(ItemStack)}).
 */
public class DecayAcceleratorBlockEntity extends AbstractAcceleratorBlockEntity {
    private static final int MIN_CHAOS = 1;
    private static final int MAX_CHAOS = 3;

    public DecayAcceleratorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.DECAY_ACCELERATOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected boolean isValidInput(ItemStack stack) {
        return ComposterBlock.getValue(stack) >= 0F;
    }

    @Override
    protected int entropyValue(ItemStack stack) {
        float chance = Math.max(0F, ComposterBlock.getValue(stack));
        return Math.max(MIN_CHAOS, Math.round(chance * MAX_CHAOS));
    }

    @Override
    protected EntropyType entropyType() {
        return EntropyType.CHAOS;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.temporalindustries.decay_accelerator");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new DecayAcceleratorMenu(id, inventory, this);
    }
}
