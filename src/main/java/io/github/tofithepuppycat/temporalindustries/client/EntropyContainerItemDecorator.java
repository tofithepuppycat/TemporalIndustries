package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyContents;
import io.github.tofithepuppycat.temporalindustries.item.EntropyContainerItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * Draws two durability-like bars over an {@link EntropyContainerItem} in inventory slots: a white
 * one for ORD (order) and a {@code #3E2A49} one for CHS (chaos), stacked like a mekanism gauge.
 */
public class EntropyContainerItemDecorator implements IItemDecorator {
    private static final int WHITE = 0xFFFFFF;
    private static final int CHAOS_COLOR = 0x3E2A49;

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        EntropyContents contents = EntropyContainerItem.getContents(stack);

        int x = xOffset + 2;
        drawBar(guiGraphics, x, yOffset + 10, contents.order(), WHITE);
        drawBar(guiGraphics, x, yOffset + 13, contents.chaos(), CHAOS_COLOR);
        return true;
    }

    private static void drawBar(GuiGraphics guiGraphics, int x, int y, int amount, int color) {
        int width = Math.round(13.0F * amount / EntropyContents.CAPACITY);
        width = Math.max(0, Math.min(13, width));
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + 13, y + 2, 0xFF000000);
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + width, y + 1, 0xFF000000 | color);
    }
}
