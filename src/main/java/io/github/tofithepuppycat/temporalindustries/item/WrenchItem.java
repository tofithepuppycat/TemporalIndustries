package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.block.entity.MachineFrameBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.WrenchConfigurable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Edits the links of a placed pylon or balancer. Right-clicking a configurable block selects it;
 * right-clicking other blocks then links or unlinks them (sneak for an output where the selected
 * block distinguishes), right-clicking the selected block again deselects it, and sneak-clicking
 * the air while a block is selected wipes its links.
 */
@SuppressWarnings("null")
public class WrenchItem extends Item {
    private static final String TAG_POS = "Selected";
    private static final String TAG_DIMENSION = "SelectedDimension";
    private static final String MESSAGE_PREFIX = "item.temporalindustries.wrench.";

    public WrenchItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockPos clicked = context.getClickedPos();
        BlockPos clickedController = resolveController(level, clicked);
        BlockEntity clickedEntity = level.getBlockEntity(clickedController);
        BlockPos selected = getSelected(stack, level);
        boolean clickedConfigurable = clickedEntity instanceof WrenchConfigurable;
        boolean selectionGone = selected != null && !selected.equals(clickedController) && isGone(level, selected);

        if (selected == null && !clickedConfigurable) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (selectionGone) {
            deselect(stack);
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "lost"), true);
            if (!clickedConfigurable) return InteractionResult.CONSUME;
            selected = null;
        }

        if (selected == null) {
            select(stack, level, clickedController);
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "selected",
                    clickedEntity.getBlockState().getBlock().getName()), true);
            return InteractionResult.CONSUME;
        }
        if (selected.equals(clickedController)) {
            deselect(stack);
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "deselected"), true);
            return InteractionResult.CONSUME;
        }
        if (!(level.getBlockEntity(selected) instanceof WrenchConfigurable configurable)) {
            deselect(stack);
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "lost"), true);
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(configurable.wrenchLink(clicked, player.isShiftKeyDown()), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            BlockPos selected = getSelected(stack, level);
            if (selected != null) {
                if (player.isShiftKeyDown() && level.getBlockEntity(selected) instanceof WrenchConfigurable configurable) {
                    player.displayClientMessage(configurable.wrenchClear(), true);
                } else {
                    deselect(stack);
                    player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "deselected"), true);
                }
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Whether the selected block is loaded and no longer a configurable block, e.g. mined away. */
    private static boolean isGone(Level level, BlockPos pos) {
        return level.isLoaded(pos) && !(level.getBlockEntity(pos) instanceof WrenchConfigurable);
    }

    /** Drops the selection once the selected block has been mined or replaced. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (level.isClientSide || level.getGameTime() % 10 != 0) return;
        BlockPos pos = getSelected(stack, level);
        if (pos == null || !isGone(level, pos)) return;
        deselect(stack);
        if (entity instanceof Player player && selected) {
            player.displayClientMessage(Component.translatable(MESSAGE_PREFIX + "lost"), true);
        }
    }

    /** A machine frame forwards to the controller it belongs to. */
    private static BlockPos resolveController(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof MachineFrameBlockEntity frame && frame.getController() != null) {
            return frame.getController();
        }
        return pos;
    }

    @Nullable
    public static BlockPos getSelected(ItemStack stack, Level level) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!data.contains(TAG_POS)) return null;
        if (!level.dimension().location().toString().equals(data.getString(TAG_DIMENSION))) return null;
        return BlockPos.of(data.getLong(TAG_POS));
    }

    private static void select(ItemStack stack, Level level, BlockPos pos) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putLong(TAG_POS, pos.asLong());
        data.putString(TAG_DIMENSION, level.dimension().location().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    private static void deselect(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.remove(TAG_POS);
        data.remove(TAG_DIMENSION);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(TAG_POS);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        TooltipUtil.appendDescription(tooltip, "item.temporalindustries.wrench.tooltip");
    }
}
