package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.device.PlayerSnapshot;
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

import java.util.List;

/**
 * Trigger device for the player temporal anchor system. All state lives server-side
 * in TemporalWorldData.PlayerTemporalState, keyed by player UUID — nothing is stored
 * in the item's NBT. Right-clicking calibrates (or re-calibrates) the player's checkpoint.
 */
@SuppressWarnings("null")
public class TemporalAnchorItem extends Item {
    public TemporalAnchorItem(Properties properties) {
        super(properties);
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
        tooltip.add(Component.translatable("item.temporalindustries.temporal_anchor.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
