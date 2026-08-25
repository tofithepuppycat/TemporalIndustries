package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
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
 * Single-type liquid entropy storage - the Order Cell and Chaos Cell, both instances of this class
 * distinguished by {@link #type}. Holds up to {@link BottleContents#CAPACITY} mB of its own fluid,
 * half of what {@link DualEntropyCellItem} holds per type, and only attracts/accepts orbs of its own
 * type. Right-clicking a machine pours a dose into it - see {@link CellTransfer}.
 */
@SuppressWarnings("null")
public class EntropyCellItem extends Item implements EntropyReceptacle {
    private final EntropyType type;

    public EntropyCellItem(EntropyType type, Properties properties) {
        super(properties);
        this.type = type;
    }

    public EntropyType getType() {
        return type;
    }

    @Override
    public boolean accepts(EntropyType type) {
        return this.type == type;
    }

    @Override
    public int capacity(EntropyType type) {
        return this.type == type ? BottleContents.CAPACITY : 0;
    }

    @Override
    public int amount(ItemStack stack, EntropyType type) {
        return this.type == type ? getContents(stack).amount() : 0;
    }

    @Override
    public int fill(ItemStack stack, EntropyType type, int millibuckets) {
        if (this.type != type || millibuckets <= 0) return 0;

        int current = getContents(stack).amount();
        int filled = Math.min(BottleContents.CAPACITY - current, millibuckets);
        if (filled <= 0) return 0;

        stack.set(Registration.BOTTLE_CONTENTS.get(), new BottleContents(current + filled));
        return filled;
    }

    @Override
    public int drain(ItemStack stack, EntropyType type, int millibuckets) {
        if (this.type != type || millibuckets <= 0) return 0;

        int current = getContents(stack).amount();
        int drained = Math.min(current, millibuckets);
        if (drained <= 0) return 0;

        stack.set(Registration.BOTTLE_CONTENTS.get(), new BottleContents(current - drained));
        return drained;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return CellTransfer.pourInto(context);
    }

    public static BottleContents getContents(ItemStack stack) {
        return stack.getOrDefault(Registration.BOTTLE_CONTENTS.get(), BottleContents.EMPTY);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int amount = getContents(stack).amount();
        String key = type == EntropyType.ORDER ? "item.temporalindustries.order_cell.contents" : "item.temporalindustries.chaos_cell.contents";
        ChatFormatting color = type == EntropyType.ORDER ? ChatFormatting.WHITE : ChatFormatting.DARK_PURPLE;
        boolean thousands = EntropyDisplay.isThousands(BottleContents.CAPACITY);
        tooltip.add(Component.translatable(key, EntropyDisplay.formatFluidScaled(amount), EntropyDisplay.formatFluidScaled(BottleContents.CAPACITY)).withStyle(color)
                .append(EntropyDisplay.unit(type, thousands)));
        CellTransfer.appendTooltip(stack, tooltip);
    }
}
