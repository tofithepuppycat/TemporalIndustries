package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Handheld tool for visiting the Overworld's past in the Chrono Rift. Right-clicking outside the
 * rift opens {@link io.github.tofithepuppycat.temporalindustries.client.screen.ChronoLensScreen} to
 * pick a moment; right-clicking while already inside the rift is a simple "return trip" back to
 * wherever the player stood before stepping in, using their stored {@link
 * PlayerTemporalState.ChronoRiftOrigin}.
 */
public class ChronoscopeItem extends Item {
    public ChronoscopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.level().dimension() == ChronoRiftDimension.LEVEL_KEY) {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                returnFromRift(serverPlayer);
            }
            return InteractionResultHolder.success(stack);
        }

        if (level.isClientSide) {
            io.github.tofithepuppycat.temporalindustries.client.screen.ChronoLensScreen.open();
        }
        return InteractionResultHolder.success(stack);
    }

    private static void returnFromRift(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        PlayerTemporalState state = data.getPlayerState(player.getUUID());
        PlayerTemporalState.ChronoRiftOrigin origin = state == null ? null : state.getChronoRiftOrigin();
        if (origin == null) {
            player.displayClientMessage(Component.translatable("item.temporalindustries.chronoscope.no_origin"), true);
            return;
        }

        ResourceKey<Level> originKey = ResourceKey.create(Registries.DIMENSION, origin.dimension());
        ServerLevel originLevel = server.getLevel(originKey);
        if (originLevel == null) originLevel = server.overworld();

        player.teleportTo(originLevel, origin.x(), origin.y(), origin.z(), origin.yRot(), origin.xRot());
        state.clearChronoRiftOrigin();
        data.setDirty();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        TooltipUtil.appendDescription(tooltip, "item.temporalindustries.chronoscope.tooltip");
    }
}
