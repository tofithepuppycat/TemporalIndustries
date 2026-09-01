package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.item.EntropyCellItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/** Draws the same durability-like bar as {@link EntropyContainerItemDecorator}, but a single one for the single-type Order/Chaos Cell. */
public class EntropyCellItemDecorator implements IItemDecorator {
    private final EntropyType type;

    public EntropyCellItemDecorator(EntropyType type) {
        this.type = type;
    }

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        int amount = EntropyCellItem.getContents(stack).amount();
        EntropyContainerItemDecorator.drawBar(guiGraphics, xOffset + 2, yOffset + 13, amount, BottleContents.CAPACITY, type.color());
        return true;
    }
}
