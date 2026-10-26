package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.GlobalPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drives the Chrono Remote Control's modes outside its screens: left-click picks chunks while
 * selecting, and any mode ends once the remote leaves the player's hands.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class ChronoRemoteClientHandler {
    private ChronoRemoteClientHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ChronoRemoteClientState.isActive()) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        GlobalPos linked = player == null ? null : ChronoRemoteControlItem.findHeldLink(player);
        boolean lost = linked == null || !linked.dimension().equals(player.level().dimension());
        if (!lost && ChronoRemoteClientState.isPreviewing()
                && TimelineProjectionManager.getSelectedCommitId() != ChronoRemoteClientState.getPreviewCommitId()) {
            lost = true;
        }
        if (lost) {
            ChronoRemoteClientState.endSession();
        }
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!ChronoRemoteClientState.isSelecting() || !event.isAttack()) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || ChronoRemoteControlItem.findHeldLink(player) == null) {
            return;
        }

        event.setCanceled(true);
        event.setSwingHand(false);
        boolean exclude = Screen.hasShiftDown() || player.isShiftKeyDown();
        PacketDistributor.sendToServer(new ChronoRemoteActionPacket(exclude
                ? ChronoRemoteActionPacket.Action.DESELECT_CHUNK
                : ChronoRemoteActionPacket.Action.SELECT_CHUNK));
    }
}
