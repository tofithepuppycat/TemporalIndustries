package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.DecayAcceleratorMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class DecayAcceleratorScreen extends AbstractAcceleratorScreen<DecayAcceleratorMenu> {
    public DecayAcceleratorScreen(DecayAcceleratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected int entropyColor() {
        return EntropyType.CHAOS.color();
    }

    @Override
    protected Component machineTitle() {
        return Component.translatable("block.temporalindustries.decay_accelerator");
    }
}
