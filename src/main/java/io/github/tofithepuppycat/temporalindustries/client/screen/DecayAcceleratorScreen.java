package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.menu.DecayAcceleratorMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class DecayAcceleratorScreen extends AbstractAcceleratorScreen<DecayAcceleratorMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/decay_accelerator.png");
    private static final ResourceLocation PROGRESS_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/decay_progress.png");

    public DecayAcceleratorScreen(DecayAcceleratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, TEXTURE, PROGRESS_TEXTURE, 78, 21, 28);
    }

    @Override
    protected Component machineTitle() {
        return Component.translatable("block.temporalindustries.decay_accelerator");
    }
}
