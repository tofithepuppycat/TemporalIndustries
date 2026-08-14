package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.screen.ChronosphereScreen;
import io.github.tofithepuppycat.temporalindustries.client.screen.TimeMachineScreen;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class ClientModEvents {
    @SuppressWarnings("null")
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(Registration.TIME_MACHINE_MENU.get(), TimeMachineScreen::new);
        event.register(Registration.CHRONOSPHERE_MENU.get(), ChronosphereScreen::new);
    }

    /** Drops the timeline/map client caches on logout — without this, a BlockPos from the world
     * just left could coincidentally match a machine's position in the next world joined, and its
     * stale commit data would render a ghost preview (see TimelineProjectionManager#clearAll)
     * that has nothing to do with the new world. Also fires when creating a new singleplayer
     * world, which should equally reset this. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TimelineProjectionManager.clearAll();
        ChronosphereClientState.clearAll();
        ChronosphereMapClientState.clearAll();
    }
}
