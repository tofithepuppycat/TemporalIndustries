package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.GlobalPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Ends the Chrono Remote Control's in-world modes once the remote leaves the player's hands. */
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

}
