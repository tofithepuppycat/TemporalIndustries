package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** {@link DescribedBlockItem} for the "Chrono-" prefixed machines (Chronovault, Chronodial,
 * Chronosphere), tinting their display name the shared Order purple so they read as one family in
 * tooltips/inventory. */
public class ChronoBlockItem extends DescribedBlockItem {

    public ChronoBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withStyle(Style.EMPTY.withColor(EntropyDisplay.ORDER_COLOR));
    }
}
