package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;

/**
 * The hand-held half of the liquid entropy plumbing: right-clicking a machine with a cell pours a
 * fixed dose of its liquid Order/Chaos into whatever {@link IFluidHandler} the machine exposes, and
 * sneak+scrolling picks how big that dose is (see
 * {@link io.github.tofithepuppycat.temporalindustries.network.CellTransferAmountPacket}).
 * Cells hook this up through {@code onItemUseFirst} so a pour beats the machine's own GUI-opening
 * right-click, while an empty cell (or a full machine) falls through and opens the GUI as usual.
 */
@SuppressWarnings("null")
public final class CellTransfer {
    /** Selectable mB-per-click doses, smallest first. */
    public static final int[] STEPS = {50, 100, 250, 500, 1_000, 2_500, 5_000};
    private static final int DEFAULT_STEP = 2;

    private CellTransfer() {}

    public static int stepIndex(ItemStack stack) {
        int index = stack.getOrDefault(Registration.CELL_TRANSFER_STEP.get(), new BottleContents(DEFAULT_STEP)).amount();
        return Math.max(0, Math.min(STEPS.length - 1, index));
    }

    /** How many mB one right-click pours out of this stack. */
    public static int amount(ItemStack stack) {
        return STEPS[stepIndex(stack)];
    }

    /** Moves the selected dose delta places up/down the {@link #STEPS} ladder, clamped at both ends.
     * Returns the newly selected dose in mB. */
    public static int cycle(ItemStack stack, int delta) {
        int index = Math.max(0, Math.min(STEPS.length - 1, stepIndex(stack) + delta));
        stack.set(Registration.CELL_TRANSFER_STEP.get(), new BottleContents(index));
        return STEPS[index];
    }

    /** Pours one dose into the clicked block's fluid handler, ORD first then CHS. Returns PASS when
     * there is nothing to pour or nowhere to pour it, so the click falls through to the block. */
    public static InteractionResult pourInto(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!(stack.getItem() instanceof EntropyReceptacle receptacle)) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        IFluidHandler target = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, context.getClickedFace());
        if (target == null) return InteractionResult.PASS;

        int dose = amount(stack);
        for (EntropyType type : EntropyType.values()) {
            int available = Math.min(dose, receptacle.amount(stack, type));
            if (available <= 0) continue;

            int accepted = target.fill(EntropyFluids.stack(type, available), IFluidHandler.FluidAction.SIMULATE);
            if (accepted <= 0) continue;

            if (!level.isClientSide) {
                target.fill(EntropyFluids.stack(type, accepted), IFluidHandler.FluidAction.EXECUTE);
                receptacle.drain(stack, type, accepted);
                markChanged(level, pos);
                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.6F, 1.4F);
                if (player != null) {
                    player.displayClientMessage(Component.translatable("item.temporalindustries.cell.poured",
                            EntropyDisplay.formatFluid(accepted)).append(EntropyDisplay.unit(type)), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** The "Right-click a machine to pour N mB" line every cell shows under its fill levels. */
    public static void appendTooltip(ItemStack stack, List<Component> tooltip) {
        tooltip.add(Component.translatable("item.temporalindustries.cell.transfer",
                EntropyDisplay.formatFluid(amount(stack))).withStyle(ChatFormatting.YELLOW));
        TooltipUtil.appendDescription(tooltip, "item.temporalindustries.cell.tooltip");
    }

    private static void markChanged(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        be.setChanged();
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }
}
