package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Single-type entropy storage — the Order Bottle and Chaos Bottle from IDEAS.md, both instances of
 * this class distinguished by {@link #type}. Smaller capacity than {@link EntropyContainerItem}
 * ({@link BottleContents#CAPACITY}) and only attracts/accepts orbs of its own type.
 */
@SuppressWarnings("null")
public class EntropyBottleItem extends Item implements EntropyReceptacle {
    private final EntropyType type;

    public EntropyBottleItem(EntropyType type, Properties properties) {
        super(properties);
        this.type = type;
    }

    @Override
    public boolean accepts(EntropyType type) {
        return this.type == type;
    }

    @Override
    public int insertOrb(ItemStack stack, EntropyType type, int amount) {
        if (this.type != type || amount <= 0) return amount;

        int current = getContents(stack).amount();
        int accepted = Math.min(BottleContents.CAPACITY - current, amount);
        if (accepted <= 0) return amount;

        stack.set(Registration.BOTTLE_CONTENTS.get(), new BottleContents(current + accepted));
        return amount - accepted;
    }

    public static BottleContents getContents(ItemStack stack) {
        return stack.getOrDefault(Registration.BOTTLE_CONTENTS.get(), BottleContents.EMPTY);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int amount = getContents(stack).amount();
        String key = type == EntropyType.ORDER ? "item.temporalindustries.order_bottle.contents" : "item.temporalindustries.chaos_bottle.contents";
        ChatFormatting color = type == EntropyType.ORDER ? ChatFormatting.WHITE : ChatFormatting.DARK_PURPLE;
        tooltip.add(Component.translatable(key, amount, BottleContents.CAPACITY).withStyle(color));
    }
}
