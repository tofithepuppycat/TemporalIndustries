package io.github.tofithepuppycat.temporalindustries.item;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** {@link BlockItem} for the "Chrono-" prefixed machines (Chronovault, Chronodial, Chronosphere),
 * tinting their display name a shared purple so they read as one family in tooltips/inventory. */
public class ChronoBlockItem extends BlockItem {
    private static final TextColor CHRONO_NAME_COLOR = TextColor.fromRgb(0xcfa0f3);

    public ChronoBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withStyle(Style.EMPTY.withColor(CHRONO_NAME_COLOR));
    }
}
