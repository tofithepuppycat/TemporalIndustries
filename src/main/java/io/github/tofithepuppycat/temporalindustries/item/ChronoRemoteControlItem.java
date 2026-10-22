package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Handheld remote for a linked Chronosphere: holding right-click opens a wheel of actions (commit,
 * jump) that run on the Chronosphere from afar.
 */
public class ChronoRemoteControlItem extends Item {
    public ChronoRemoteControlItem(Properties properties) {
        super(properties);
    }

    /** The Chronosphere stack is linked to, or null if it hasn't been linked yet. */
    @Nullable
    public static GlobalPos getLinkedChronosphere(ItemStack stack) {
        return stack.get(Registration.LINKED_CHRONOSPHERE.get());
    }

    public static void link(ItemStack stack, GlobalPos chronosphere) {
        stack.set(Registration.LINKED_CHRONOSPHERE.get(), chronosphere);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (getLinkedChronosphere(stack) == null) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("item.temporalindustries.chrono_remote_control.not_linked"), true);
            }
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide) {
            io.github.tofithepuppycat.temporalindustries.client.screen.ChronoRemoteWheelScreen.open();
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos linked = getLinkedChronosphere(stack);
        if (linked != null) {
            tooltip.add(Component.translatable("item.temporalindustries.chrono_remote_control.linked",
                    linked.pos().getX() + ", " + linked.pos().getY() + ", " + linked.pos().getZ()));
        }
        TooltipUtil.appendDescription(tooltip,
                "item.temporalindustries.chrono_remote_control.tooltip",
                "item.temporalindustries.chrono_remote_control.tooltip_link");
    }
}
