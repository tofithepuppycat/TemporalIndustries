package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.DualEntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.item.EntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem;
import io.github.tofithepuppycat.temporalindustries.network.AnchorModeCyclePacket;
import io.github.tofithepuppycat.temporalindustries.network.CellTransferAmountPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** While sneaking, the scroll wheel configures the held item instead of changing hotbar slot: a
 * Temporal Anchor cycles its rewind mode, a cell picks how much liquid entropy one right-click
 * pours into a machine. */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class SneakScrollInputHandler {
    private SneakScrollInputHandler() {}

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isShiftKeyDown()) return;
        if (event.getScrollDeltaY() == 0) return;

        Item item = player.getMainHandItem().getItem();
        boolean isCell = item instanceof EntropyCellItem || item instanceof DualEntropyCellItem;
        if (!isCell && !(item instanceof TemporalAnchorItem)) return;

        event.setCanceled(true);
        int delta = event.getScrollDeltaY() > 0 ? 1 : -1;
        if (isCell) {
            PacketDistributor.sendToServer(new CellTransferAmountPacket(delta));
        } else {
            PacketDistributor.sendToServer(new AnchorModeCyclePacket(delta));
        }
    }
}
