package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.menu.DespawnAcceleratorMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class DespawnAcceleratorScreen extends AbstractAcceleratorScreen<DespawnAcceleratorMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/despawn_accelerator.png");
    private static final ResourceLocation PROGRESS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/despawn_progress.png");

    public DespawnAcceleratorScreen(DespawnAcceleratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, TEXTURE, PROGRESS_TEXTURE, 79, 28, 18);
    }

    @Override
    protected Component machineTitle() {
        return Component.translatable("block.temporalindustries.despawn_accelerator");
    }
}
