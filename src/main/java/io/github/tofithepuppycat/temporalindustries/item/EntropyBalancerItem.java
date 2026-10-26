package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyBalancerBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Held in hand, right-clicking a machine with an entropy balance marks it as the output; the mark
 * is handed to the balancer when it is placed, or to an already placed balancer that is
 * right-clicked with the marked item. Sneaking skips marking so the item can still be placed
 * against such a machine.
 */
public class EntropyBalancerItem extends BlockItem {
    private static final String TAG_OUTPUT = "Output";
    private static final String MESSAGE_PREFIX = "item.temporalindustries.entropy_balancer.";

    public EntropyBalancerItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** Runs before the target block's own interaction, so a machine is marked instead of opening its GUI. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        if (player == null || player.isShiftKeyDown()) return InteractionResult.PASS;

        BlockPos marked = getMark(stack);
        if (marked != null && level.getBlockEntity(pos) instanceof EntropyBalancerBlockEntity balancer) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + (balancer.bindOutput(marked) ? "bound" : "too_far"),
                        EntropyBalancerBlockEntity.BIND_RANGE), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (isValidOutput(level, pos)) {
            if (!level.isClientSide) {
                setMark(stack, pos);
                player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "marked", pos.getX(), pos.getY(), pos.getZ()), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    private static boolean isValidOutput(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof EntropyInfoProvider provider && provider.hasEntropyBalance()
                && level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null;
    }

    /** Right-clicking in the air forgets the marked output. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && getMark(stack) != null) {
            CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            data.remove(TAG_OUTPUT);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "cleared"), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, @Nullable Player player, ItemStack stack, BlockState state) {
        boolean result = super.updateCustomBlockEntityTag(pos, level, player, stack, state);
        BlockPos marked = getMark(stack);
        if (!level.isClientSide && marked != null && level.getBlockEntity(pos) instanceof EntropyBalancerBlockEntity be) {
            if (be.bindOutput(marked)) {
                if (player != null) player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "bound"), true);
            } else if (player != null) {
                player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "too_far", EntropyBalancerBlockEntity.BIND_RANGE), true);
            }
        }
        return result;
    }

    @Nullable
    public static BlockPos getMark(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.contains(TAG_OUTPUT) ? BlockPos.of(data.getLong(TAG_OUTPUT)) : null;
    }

    private static void setMark(ItemStack stack, BlockPos pos) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putLong(TAG_OUTPUT, pos.asLong());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        BlockPos marked = getMark(stack);
        if (marked != null) {
            tooltip.add(Component.translatable(MESSAGE_PREFIX + "output", marked.getX() + ", " + marked.getY() + ", " + marked.getZ()));
        }
        TooltipUtil.appendDescription(tooltip,
                "block.temporalindustries.entropy_balancer.tooltip",
                "block.temporalindustries.entropy_balancer.tooltip_bind");
    }
}
