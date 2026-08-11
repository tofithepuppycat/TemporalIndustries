package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.screen.ChronosphereScreen;
import io.github.tofithepuppycat.temporalindustries.client.screen.TimeMachineScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class ClientModEvents {
    @SuppressWarnings("null")
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(Registration.TIME_MACHINE_MENU.get(), TimeMachineScreen::new);
        event.register(Registration.CHRONOSPHERE_MENU.get(), ChronosphereScreen::new);
    }
}
