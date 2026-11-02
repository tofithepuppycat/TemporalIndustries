package io.github.tofithepuppycat.temporalindustries.item;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** The Creative Chronosphere item: plain rarity-coloured name, no description, always enchanted-glinting. */
public class CreativeChronosphereItem extends BlockItem {
    public CreativeChronosphereItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
