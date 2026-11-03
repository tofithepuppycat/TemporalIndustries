package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** An item whose name is drawn in the colour of the entropy it is made of, wherever its name shows. */
public class EntropyColoredItem extends Item {
    private final EntropyType type;

    public EntropyColoredItem(Properties properties, EntropyType type) {
        super(properties);
        this.type = type;
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withStyle(Style.EMPTY.withColor(type.color()));
    }
}
