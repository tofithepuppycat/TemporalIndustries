package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.AntiTemporalGlueItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalGlueItem;
import io.github.tofithepuppycat.temporalindustries.network.GlueUnglueRequestPacket;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Tells the server when a glue is punched at empty air, since only the client sees that click. */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class GlueUnglueClientHandler {
    private GlueUnglueClientHandler() {}

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        for (ItemStack stack : new ItemStack[] {event.getEntity().getMainHandItem(), event.getEntity().getOffhandItem()}) {
            if (stack.getItem() instanceof AntiTemporalGlueItem || stack.getItem() instanceof TemporalGlueItem) {
                PacketDistributor.sendToServer(GlueUnglueRequestPacket.INSTANCE);
                return;
            }
        }
    }
}
