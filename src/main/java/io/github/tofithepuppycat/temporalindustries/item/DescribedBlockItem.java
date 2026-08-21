package io.github.tofithepuppycat.temporalindustries.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** {@link BlockItem} that appends a Shift-gated description built from the block's own registry
 * name (block.&lt;namespace&gt;.&lt;path&gt;.tooltip), so a plain machine block doesn't need its
 * own Item subclass just to show a description. */
public class DescribedBlockItem extends BlockItem {
    public DescribedBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(getBlock());
        TooltipUtil.appendDescription(tooltip, "block." + key.getNamespace() + "." + key.getPath() + ".tooltip");
    }
}
