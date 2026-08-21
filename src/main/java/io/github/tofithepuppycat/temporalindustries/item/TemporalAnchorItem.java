package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.device.PlayerSnapshot;
import io.github.tofithepuppycat.temporalindustries.entropy.BottleContents;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
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
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Trigger device for the player temporal anchor system. All checkpoint/world-change state lives
 * server-side in TemporalWorldData.PlayerTemporalState, keyed by player UUID. The stack itself only
 * holds the order bar it's charged with (by {@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyChargingService})
 * and its rewind mode, both as data components — right-clicking calibrates (or re-calibrates) the
 * player's checkpoint; dying with enough order banked spends it to rewind (see
 * {@link io.github.tofithepuppycat.temporalindustries.device.TemporalChangeListener#onPlayerDeath}).
 */
@SuppressWarnings("null")
public class TemporalAnchorItem extends Item {
    /** 100 raw = 10.0 order, matching the EntropyDisplay raw/10 convention. */
    public static final int MAX_ORDER = 100;
    public static final int MODE_REWIND_ALL = 0;
    public static final int MODE_KEEP_INVENTORY = 1;
    public static final int MODE_COUNT = 2;

    public TemporalAnchorItem(Properties properties) {
        super(properties);
    }

    public static int getOrder(ItemStack stack) {
        return stack.getOrDefault(Registration.ANCHOR_ORDER.get(), BottleContents.EMPTY).amount();
    }

    public static void setOrder(ItemStack stack, int raw) {
        stack.set(Registration.ANCHOR_ORDER.get(), new BottleContents(Math.max(0, Math.min(MAX_ORDER, raw))));
    }

    public static int getMode(ItemStack stack) {
        return stack.getOrDefault(Registration.ANCHOR_MODE.get(), BottleContents.EMPTY).amount();
    }

    public static void setMode(ItemStack stack, int mode) {
        stack.set(Registration.ANCHOR_MODE.get(), new BottleContents(Math.floorMod(mode, MODE_COUNT)));
    }

    /** 1.0 order for a full rewind, 2.0 for the more convenient keep-inventory rewind. */
    public static int costForMode(int mode) {
        return mode == MODE_KEEP_INVENTORY ? 20 : 10;
    }

    /** First Temporal Anchor stack (main inventory or offhand) carrying enough order to pay for
     * its own mode's cost, or null if none qualifies — the anchor that pays for a death rewind. */
    @Nullable
    public static ItemStack findChargedAnchor(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (isCharged(stack)) return stack;
        }
        ItemStack offhand = player.getOffhandItem();
        if (isCharged(offhand)) return offhand;
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
                        EntropyDisplay.format(getOrder(stack)), EntropyDisplay.format(MAX_ORDER))
                .withStyle(ChatFormatting.WHITE)
                .append(EntropyDisplay.unit(EntropyType.ORDER)));

        String modeKey = getMode(stack) == MODE_KEEP_INVENTORY
                ? "item.temporalindustries.temporal_anchor.mode_keep_inventory"
                : "item.temporalindustries.temporal_anchor.mode_rewind_all";
        tooltip.add(Component.translatable(modeKey).withStyle(ChatFormatting.YELLOW));

        TooltipUtil.appendDescription(tooltip, "item.temporalindustries.temporal_anchor.tooltip");
    }
}
