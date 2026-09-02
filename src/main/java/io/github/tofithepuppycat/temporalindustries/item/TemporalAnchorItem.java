package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCompat;
import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.device.PlayerSnapshot;
import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Trigger device for the player temporal anchor system. All checkpoint/world-change state lives
 * server-side in TemporalWorldData.PlayerTemporalState, keyed by player UUID. The stack itself only
 * holds the liquid order it's charged with - poured in by
 * {@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyChargingService}, caught
 * straight out of ORD orbs like a cell, or filled by any fluid handler through its item capability -
 * and its rewind mode, both as data components — right-clicking calibrates (or re-calibrates) the
 * player's checkpoint; dying with enough order banked spends it to rewind (see
 * {@link io.github.tofithepuppycat.temporalindustries.device.TemporalChangeListener#onPlayerDeath}).
 */
@SuppressWarnings("null")
public class TemporalAnchorItem extends Item implements EntropyReceptacle {
    /** Anchor tank size, in mB of liquid Order - the same scale machine tanks and cells use. */
    public static final int MAX_ORDER_MB = 5_000;
    public static final int MODE_REWIND_ALL = 0;
    public static final int MODE_KEEP_INVENTORY = 1;
    public static final int MODE_COUNT = 2;

    public TemporalAnchorItem(Properties properties) {
        super(properties);
    }

    public static int getOrder(ItemStack stack) {
        return stack.getOrDefault(Registration.ANCHOR_ORDER.get(), BottleContents.EMPTY).amount();
    }

    public static void setOrder(ItemStack stack, int millibuckets) {
        stack.set(Registration.ANCHOR_ORDER.get(), new BottleContents(Math.max(0, Math.min(MAX_ORDER_MB, millibuckets))));
    }

    public static int getMode(ItemStack stack) {
        return stack.getOrDefault(Registration.ANCHOR_MODE.get(), BottleContents.EMPTY).amount();
    }

    public static void setMode(ItemStack stack, int mode) {
        stack.set(Registration.ANCHOR_MODE.get(), new BottleContents(Math.floorMod(mode, MODE_COUNT)));
    }

    /** 500 mB of Order for a full rewind, 1,000 for the more convenient keep-inventory rewind. */
    public static int costForMode(int mode) {
        return mode == MODE_KEEP_INVENTORY ? 1_000 : 500;
    }

    // --- EntropyReceptacle: the anchor is an order-only tank, so it catches ORD orbs like a cell ---

    @Override
    public boolean accepts(EntropyType type) {
        return type == EntropyType.ORDER;
    }

    @Override
    public int capacity(EntropyType type) {
        return type == EntropyType.ORDER ? MAX_ORDER_MB : 0;
    }

    @Override
    public int amount(ItemStack stack, EntropyType type) {
        return type == EntropyType.ORDER ? getOrder(stack) : 0;
    }

    @Override
    public int fill(ItemStack stack, EntropyType type, int millibuckets) {
        if (type != EntropyType.ORDER || millibuckets <= 0) return 0;

        int current = getOrder(stack);
        int filled = Math.min(MAX_ORDER_MB - current, millibuckets);
        if (filled <= 0) return 0;

        setOrder(stack, current + filled);
        return filled;
    }

    @Override
    public int drain(ItemStack stack, EntropyType type, int millibuckets) {
        if (type != EntropyType.ORDER || millibuckets <= 0) return 0;

        int current = getOrder(stack);
        int drained = Math.min(current, millibuckets);
        if (drained <= 0) return 0;

        setOrder(stack, current - drained);
        return drained;
    }

    /** First Temporal Anchor stack (main inventory, offhand, or Curios charm slot) carrying enough
     * order to pay for its own mode's cost, or null if none qualifies — the anchor that pays for a
     * death rewind. */
    @Nullable
    public static ItemStack findChargedAnchor(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (isCharged(stack)) return stack;
        }
        ItemStack offhand = player.getOffhandItem();
        if (isCharged(offhand)) return offhand;
        if (ModList.get().isLoaded("curios")) {
            ItemStack curioAnchor = CuriosCompat.findEquippedTemporalAnchor(player);
            if (curioAnchor != null && isCharged(curioAnchor)) return curioAnchor;
        }
        return null;
    }

    private static boolean isCharged(ItemStack stack) {
        return stack.getItem() instanceof TemporalAnchorItem && getOrder(stack) >= costForMode(getMode(stack));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }

        MinecraftServer server = serverPlayer.getServer();
        if (server == null) return InteractionResultHolder.success(stack);

        PlayerTemporalState state = TemporalWorldData.get(server)
                .getOrCreatePlayerState(serverPlayer.getUUID());
        state.setCheckpoint(PlayerSnapshot.capture(serverPlayer));
        TemporalWorldData.get(server).setDirty();

        serverPlayer.displayClientMessage(
                Component.translatable("item.temporalindustries.temporal_anchor.calibrated"), true);
        level.playSound(null, serverPlayer.blockPosition(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 1.5F);

        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.temporalindustries.temporal_anchor.order",
                        EntropyDisplay.formatFluid(getOrder(stack)), EntropyDisplay.formatFluid(MAX_ORDER_MB))
                .withStyle(ChatFormatting.WHITE)
                .append(EntropyDisplay.unit(EntropyType.ORDER)));

        String modeKey = getMode(stack) == MODE_KEEP_INVENTORY
                ? "item.temporalindustries.temporal_anchor.mode_keep_inventory"
                : "item.temporalindustries.temporal_anchor.mode_rewind_all";
        tooltip.add(Component.translatable(modeKey).withStyle(ChatFormatting.YELLOW));

        TooltipUtil.appendDescription(tooltip, "item.temporalindustries.temporal_anchor.tooltip");
    }
}
