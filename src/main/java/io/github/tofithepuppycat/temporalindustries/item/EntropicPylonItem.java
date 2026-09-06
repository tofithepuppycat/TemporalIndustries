package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.block.entity.EntropicPylonBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

import java.util.ArrayList;
import java.util.List;

/** Held in hand, marks up to {@link #MAX_MARKS} input blocks (plain right-click) and, separately,
 * output blocks (shift-right-click) that liquid Order/Chaos will flow between once placed and
 * completed by a {@link io.github.tofithepuppycat.temporalindustries.block.MachineFrame} - see
 * {@link EntropicPylonBlockEntity}. Right-clicking anything that isn't a valid mark target (no
 * fluid handler capability) falls straight through to placing the block as normal. Marks are held
 * on the item itself until placement, at which point whatever's still within {@link #RANGE} of the
 * placed pylon is handed off to the block entity - see {@link #applyMarks}. */
public class EntropicPylonItem extends BlockItem {
    /** Cap on how many blocks a single pylon item can have marked as inputs, and separately as
     * outputs, before it's placed. */
    public static final int MAX_MARKS = 4;
    /** Half-width (in blocks) of the cube around the placed pylon that marks must fall inside to
     * survive placement - an 11x11x11 volume. */
    public static final int RANGE = 5;

    private static final String TAG_INPUTS = "Inputs";
    private static final String TAG_OUTPUTS = "Outputs";

    public EntropicPylonItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** Runs before the target block gets any say in the interaction (its GUI-opening
     * {@code useWithoutItem}, in particular) - without this, right-clicking a machine to mark it as
     * an input/output would just open that machine's own menu instead. Falls through to normal
     * placement ({@link #useOn}) for anything that isn't a valid mark target. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();

        if (player != null && isValidTarget(level, pos)) {
            if (!level.isClientSide) {
                mark(stack, pos, player.isShiftKeyDown(), player, translationPrefix());
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        return InteractionResult.PASS;
    }

    private static boolean isValidTarget(Level level, BlockPos pos) {
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null;
    }

    /** Right-clicking in the air (nothing for {@link #onItemUseFirst}/{@link #useOn} to mark or
     * place against) wipes every recorded input/output mark instead, giving players a way to start
     * over without needing to re-mark the opposite list on every block first. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            clearMarks(stack, player, translationPrefix());
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** {@code "item.temporalindustries.entropic_pylon"} (or {@code chaos_pylon}/{@code order_pylon}
     * for the single-liquid variants) - derived from this item's own registry name so the shared
     * marking logic below reports status using the right block's translations. */
    private String translationPrefix() {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(getBlock());
        return "item." + key.getNamespace() + "." + key.getPath();
    }

    private static void clearMarks(ItemStack stack, Player player, String translationPrefix) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.remove(TAG_INPUTS);
        data.remove(TAG_OUTPUTS);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        player.displayClientMessage(Component.translatable(translationPrefix + ".cleared"), true);
    }

    /** Adds {@code pos} to the input list (plain click) or output list (shift click), removing it
     * from the other list first - a block can't be both at once. Once a list is at {@link #MAX_MARKS},
     * the oldest mark is dropped to make room for the new one. */
    private static void mark(ItemStack stack, BlockPos pos, boolean output, Player player, String translationPrefix) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        List<BlockPos> same = readList(data, output ? TAG_OUTPUTS : TAG_INPUTS);
        List<BlockPos> opposite = readList(data, output ? TAG_INPUTS : TAG_OUTPUTS);

        if (!same.contains(pos)) {
            opposite.remove(pos);
            if (same.size() >= MAX_MARKS) same.remove(0);
            same.add(pos.immutable());
        }

        writeList(data, output ? TAG_OUTPUTS : TAG_INPUTS, same);
        writeList(data, output ? TAG_INPUTS : TAG_OUTPUTS, opposite);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));

        player.displayClientMessage(Component.translatable(translationPrefix + (output ? ".marked_output" : ".marked_input"),
                same.size(), MAX_MARKS), true);
    }

    /** Currently marked input positions on {@code stack}, for
     * {@link io.github.tofithepuppycat.temporalindustries.client.EntropicPylonMarkRenderer} to
     * outline while the item is held. */
    public static List<BlockPos> getInputs(ItemStack stack) {
        return readList(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(), TAG_INPUTS);
    }

    /** Currently marked output positions on {@code stack} - see {@link #getInputs}. */
    public static List<BlockPos> getOutputs(ItemStack stack) {
        return readList(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(), TAG_OUTPUTS);
    }

    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, @Nullable Player player, ItemStack stack, BlockState state) {
        boolean result = super.updateCustomBlockEntityTag(pos, level, player, stack, state);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof EntropicPylonBlockEntity be) {
            applyMarks(stack, be, pos);
        }
        return result;
    }

    /** Hands whatever marks are still within {@link #RANGE} of the placed position off to the new
     * block entity - marks recorded somewhere else entirely are silently dropped rather than
     * transferring entropy across the map. */
    private static void applyMarks(ItemStack stack, EntropicPylonBlockEntity be, BlockPos placedAt) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        be.setMarks(filterInRange(readList(data, TAG_INPUTS), placedAt), filterInRange(readList(data, TAG_OUTPUTS), placedAt));
    }

    private static List<BlockPos> filterInRange(List<BlockPos> list, BlockPos center) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : list) {
            if (Math.abs(pos.getX() - center.getX()) <= RANGE
                    && Math.abs(pos.getY() - center.getY()) <= RANGE
                    && Math.abs(pos.getZ() - center.getZ()) <= RANGE) {
                result.add(pos);
            }
        }
        return result;
    }

    private static List<BlockPos> readList(CompoundTag data, String key) {
        List<BlockPos> list = new ArrayList<>();
        if (!data.contains(key)) return list;
        for (long packed : data.getLongArray(key)) list.add(BlockPos.of(packed));
        return list;
    }

    private static void writeList(CompoundTag data, String key, List<BlockPos> list) {
        long[] array = new long[list.size()];
        for (int i = 0; i < list.size(); i++) array[i] = list.get(i).asLong();
        data.put(key, new LongArrayTag(array));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(getBlock());
        TooltipUtil.appendDescription(tooltip, "block." + key.getNamespace() + "." + key.getPath() + ".tooltip");
    }
}
