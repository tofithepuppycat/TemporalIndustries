package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * Holds liquid ORD (order) and CHS (chaos) condensed out of the
 * {@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity} orbs it catches, up
 * to {@link EntropyContents#CAPACITY} mB of each. A player only attracts and picks up entropy orbs
 * while holding an {@link EntropyReceptacle} that accepts the orb's type - see
 * {@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity#isHoldingReceptacle}.
 * Right-clicking a machine pours a dose of either fluid into it (see {@link CellTransfer}); the two
 * fill levels are drawn as stacked bars over the item icon by
 * {@link io.github.tofithepuppycat.temporalindustries.client.EntropyContainerItemDecorator}.
 */
@SuppressWarnings("null")
public class DualEntropyCellItem extends Item implements EntropyReceptacle {

    public DualEntropyCellItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean accepts(EntropyType type) {
        return true;
    }

    @Override
    public int capacity(EntropyType type) {
        return EntropyContents.CAPACITY;
    }

    @Override
    public int amount(ItemStack stack, EntropyType type) {
        return getContents(stack).amount(type);
    }

    @Override
    public int fill(ItemStack stack, EntropyType type, int millibuckets) {
        if (!(stack.getItem() instanceof DualEntropyCellItem) || millibuckets <= 0) return 0;

        EntropyContents contents = getContents(stack);
        int current = contents.amount(type);
        int filled = Math.min(EntropyContents.CAPACITY - current, millibuckets);
        if (filled <= 0) return 0;

        stack.set(Registration.ENTROPY_CONTENTS.get(), contents.with(type, current + filled));
        return filled;
    }

    @Override
    public int drain(ItemStack stack, EntropyType type, int millibuckets) {
        if (!(stack.getItem() instanceof DualEntropyCellItem) || millibuckets <= 0) return 0;

        EntropyContents contents = getContents(stack);
        int current = contents.amount(type);
        int drained = Math.min(current, millibuckets);
        if (drained <= 0) return 0;

        stack.set(Registration.ENTROPY_CONTENTS.get(), contents.with(type, current - drained));
        return drained;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return CellTransfer.pourInto(context);
    }

    public static EntropyContents getContents(ItemStack stack) {
        return stack.getOrDefault(Registration.ENTROPY_CONTENTS.get(), EntropyContents.EMPTY);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        EntropyContents contents = getContents(stack);
        tooltip.add(EntropyDisplay.amountOverCapacity(contents.order(), EntropyContents.CAPACITY, EntropyType.ORDER).withStyle(ChatFormatting.WHITE));
        tooltip.add(EntropyDisplay.amountOverCapacity(contents.chaos(), EntropyContents.CAPACITY, EntropyType.CHAOS).withStyle(ChatFormatting.DARK_PURPLE));
        CellTransfer.appendTooltip(stack, tooltip);
    }
}
