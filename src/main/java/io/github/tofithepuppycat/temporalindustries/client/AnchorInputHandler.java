package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem;
import io.github.tofithepuppycat.temporalindustries.network.AnchorModeCyclePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** While sneaking and holding a Temporal Anchor, the scroll wheel cycles its rewind mode instead
 * of the hotbar slot. */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class AnchorInputHandler {
    private AnchorInputHandler() {}

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isShiftKeyDown()) return;
        if (!(player.getMainHandItem().getItem() instanceof TemporalAnchorItem)) return;
        if (event.getScrollDeltaY() == 0) return;

        event.setCanceled(true);
        int delta = event.getScrollDeltaY() > 0 ? 1 : -1;
        PacketDistributor.sendToServer(new AnchorModeCyclePacket(delta));
    }
}
