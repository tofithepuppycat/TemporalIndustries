package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Client-side reaction to {@link io.github.tofithepuppycat.temporalindustries.network.AnchorStatusPacket}.
 * Only ever invoked from that packet's handle() callback, which only fires on the client - kept
 * in its own class (mirroring {@link io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager})
 * so the packet class itself never needs to reference client-only types.
 */
public final class AnchorClientHandler {
    private AnchorClientHandler() {
    }

    public static void onReverted(int revertedChangeCount, int foodLevel, float saturation, float exhaustion) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        player.getFoodData().setFoodLevel(foodLevel);
        player.getFoodData().setSaturation(saturation);
        player.getFoodData().setExhaustion(exhaustion);

        minecraft.gui.setTimes(10, 70, 20);
        minecraft.gui.setTitle(Component.translatable("temporalindustries.anchor.reverted.title"));
        minecraft.gui.setSubtitle(Component.translatable("temporalindustries.anchor.reverted.subtitle", revertedChangeCount));
    }
}
