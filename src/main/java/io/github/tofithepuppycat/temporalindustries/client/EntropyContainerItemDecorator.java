package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.item.DualEntropyCellItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * Draws two durability-like bars over a {@link DualEntropyCellItem} in inventory slots: a white
 * one for ORD (order) and a {@code #3E2A49} one for CHS (chaos), stacked like a mekanism gauge.
 */
public class EntropyContainerItemDecorator implements IItemDecorator {
    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        EntropyContents contents = DualEntropyCellItem.getContents(stack);

        int x = xOffset + 2;
        drawBar(guiGraphics, x, yOffset + 10, contents.order(), EntropyContents.CAPACITY, EntropyType.ORDER.color());
        drawBar(guiGraphics, x, yOffset + 13, contents.chaos(), EntropyContents.CAPACITY, EntropyType.CHAOS.color());
        return true;
    }

    static void drawBar(GuiGraphics guiGraphics, int x, int y, int amount, int capacity, int color) {
        int width = Math.round(13.0F * amount / capacity);
        width = Math.max(0, Math.min(13, width));
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + 13, y + 2, 0xFF000000);
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + width, y + 1, 0xFF000000 | color);
    }
}
