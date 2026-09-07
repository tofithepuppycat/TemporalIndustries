package io.github.tofithepuppycat.temporalindustries.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Curios headwear (no armor value) that lets the wearer see entropy info about the block they're looking at. */
public class EntropyGlassesItem extends Item {
    public EntropyGlassesItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        TooltipUtil.appendDescription(tooltip, "item.temporalindustries.entropy_glasses.tooltip");
    }
}
