package io.github.tofithepuppycat.temporalindustries.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Worn in the helmet slot; grants no defense, only lets the wearer see entropy info about the block
 * they're looking at (see EntropyGlassesOverlay client-side and EntropyInfoProvider on block entities). */
public class EntropyGlassesItem extends ArmorItem {
    public EntropyGlassesItem(Holder<ArmorMaterial> material, Type type, Properties properties) {
        super(material, type, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.temporalindustries.entropy_glasses.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
