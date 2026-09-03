package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.DespawnAcceleratorMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class DespawnAcceleratorScreen extends AbstractAcceleratorScreen<DespawnAcceleratorMenu> {
    public DespawnAcceleratorScreen(DespawnAcceleratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected int entropyColor() {
        return EntropyType.ORDER.color();
    }

    @Override
    protected Component machineTitle() {
        return Component.translatable("block.temporalindustries.despawn_accelerator");
    }
}
