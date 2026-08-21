package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/** Draws a single white durability-like bar over a Temporal Anchor showing its banked order,
 * mirroring {@link EntropyContainerItemDecorator}'s bars. */
public class TemporalAnchorItemDecorator implements IItemDecorator {
    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        int order = TemporalAnchorItem.getOrder(stack);
        EntropyContainerItemDecorator.drawBar(guiGraphics, xOffset + 2, yOffset + 13, order, TemporalAnchorItem.MAX_ORDER, EntropyType.ORDER.color());
        return true;
    }
}
