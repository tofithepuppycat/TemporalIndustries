package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.screen.ChronosphereScreen;
import io.github.tofithepuppycat.temporalindustries.client.screen.EntropyCondenserScreen;
import io.github.tofithepuppycat.temporalindustries.client.screen.TimeMachineScreen;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class ClientModEvents {
    @SuppressWarnings("null")
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(Registration.TIME_MACHINE_MENU.get(), TimeMachineScreen::new);
        event.register(Registration.CHRONOSPHERE_MENU.get(), ChronosphereScreen::new);
        event.register(Registration.ENTROPY_CONDENSER_MENU.get(), EntropyCondenserScreen::new);
    }

    @SuppressWarnings("null")
    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture() { return ResourceLocation.withDefaultNamespace("block/water_still"); }
            @Override public ResourceLocation getFlowingTexture() { return ResourceLocation.withDefaultNamespace("block/water_flow"); }
            @Override public int getTintColor() { return 0xFF000000 | EntropyType.ORDER.color(); }
        }, Registration.ORDER_FLUID_TYPE.get());

        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture() { return ResourceLocation.withDefaultNamespace("block/water_still"); }
            @Override public ResourceLocation getFlowingTexture() { return ResourceLocation.withDefaultNamespace("block/water_flow"); }
            @Override public int getTintColor() { return 0xFF000000 | EntropyType.CHAOS.color(); }
        }, Registration.CHAOS_FLUID_TYPE.get());
    }

    @SuppressWarnings("null")
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Registration.ENTROPY_ORB.get(), EntropyOrbRenderer::new);
    }

    @SuppressWarnings("null")
    @SubscribeEvent
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        event.register(Registration.ENTROPY_CONTAINER_ITEM.get(), new EntropyContainerItemDecorator());
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
        ChunkThumbnailClientState.clearAll();
        GlueSelectionClientState.clear();
    }
}
