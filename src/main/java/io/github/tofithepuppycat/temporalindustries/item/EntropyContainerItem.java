package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Holds ORD (order) and CHS (chaos) picked up from {@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity}
 * orbs, up to {@link EntropyContents#CAPACITY} of each. A player only attracts and picks up entropy
 * orbs while holding one of these, see {@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity#isHoldingContainer}.
 * The two fill levels are drawn as stacked bars over the item icon by
 * {@link io.github.tofithepuppycat.temporalindustries.client.EntropyContainerItemDecorator}.
 */
@SuppressWarnings("null")
public class EntropyContainerItem extends Item {

    public EntropyContainerItem(Properties properties) {
        super(properties);
    }

    /** Adds amount of type into stack, respecting capacity. Returns whatever didn't fit. */
    public static int insert(ItemStack stack, EntropyType type, int amount) {
        if (!(stack.getItem() instanceof EntropyContainerItem) || amount <= 0) return amount;

        EntropyContents contents = getContents(stack);
        int current = contents.amount(type);
        int accepted = Math.min(EntropyContents.CAPACITY - current, amount);
        if (accepted <= 0) return amount;

        stack.set(Registration.ENTROPY_CONTENTS.get(), contents.with(type, current + accepted));
        return amount - accepted;
    }

    public static EntropyContents getContents(ItemStack stack) {
        return stack.getOrDefault(Registration.ENTROPY_CONTENTS.get(), EntropyContents.EMPTY);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        EntropyContents contents = getContents(stack);
        tooltip.add(Component.translatable("item.temporalindustries.entropy_container.order", contents.order(), EntropyContents.CAPACITY)
                .withStyle(ChatFormatting.WHITE));
        tooltip.add(Component.translatable("item.temporalindustries.entropy_container.chaos", contents.chaos(), EntropyContents.CAPACITY)
                .withStyle(ChatFormatting.DARK_PURPLE));
    }
}
