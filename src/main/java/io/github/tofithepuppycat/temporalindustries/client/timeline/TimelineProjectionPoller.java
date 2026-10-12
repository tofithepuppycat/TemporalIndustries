package io.github.tofithepuppycat.temporalindustries.client.timeline;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Keeps the in-world "Show Changes" ghost preview (see {@link TimelineProjectionRenderer}) fresh
 * even after the machine's GUI has closed.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class TimelineProjectionPoller {
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static int ticksSinceSync = 0;

    private TimelineProjectionPoller() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!TimelineProjectionManager.hasActivePreview()) {
            ticksSinceSync = 0;
            return;
        }

        BlockPos machinePos = TimelineProjectionManager.getActiveMachinePos();
        if (machinePos == null || Minecraft.getInstance().player == null) {
            return;
        }

        ticksSinceSync++;
        if (ticksSinceSync < SYNC_INTERVAL_TICKS) {
            return;
        }
        ticksSinceSync = 0;

        PacketDistributor.sendToServer(new TimelinePreviewRequestPacket(
                machinePos, TimelineProjectionManager.getHeadCommitId(), TimelineProjectionManager.getPreviewVersion(),
                TimelineProjectionManager.getSelectedCommitId()));
    }
}
