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

/** The pylon block items; their links are set with the wrench once placed. */
public class EntropicPylonItem extends BlockItem {
    /** Most blocks a single pylon can have linked as inputs, and separately as outputs. */
    public static final int MAX_MARKS = 4;
    /** Half-width (in blocks) of the cube around a pylon that its links must fall inside (an 11x11x11 volume). */
    public static final int RANGE = 5;

    public EntropicPylonItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(getBlock());
        TooltipUtil.appendDescription(tooltip, "block." + key.getNamespace() + "." + key.getPath() + ".tooltip");
    }
}
