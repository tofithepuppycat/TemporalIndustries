package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCellCompat;
import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

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

    /** Right-clicking air equips this cell into an empty Cell curio slot, if Curios is loaded and
     * there's room - a shortcut for what would otherwise be a shift-click in the Curios UI. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && ModList.get().isLoaded("curios") && CuriosCellCompat.equipInEmptySlot(player, stack)) {
            stack.shrink(1);
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    public static BottleContents getContents(ItemStack stack) {
        return stack.getOrDefault(Registration.BOTTLE_CONTENTS.get(), BottleContents.EMPTY);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int amount = getContents(stack).amount();
        String key = type == EntropyType.ORDER ? "item.temporalindustries.order_cell.contents" : "item.temporalindustries.chaos_cell.contents";
        ChatFormatting color = type == EntropyType.ORDER ? ChatFormatting.WHITE : ChatFormatting.DARK_PURPLE;
        tooltip.add(Component.translatable(key, EntropyDisplay.formatFluid(amount), EntropyDisplay.formatFluid(BottleContents.CAPACITY)).withStyle(color)
                .append(EntropyDisplay.unit(type)));
        CellTransfer.appendTooltip(stack, tooltip);
    }
}
