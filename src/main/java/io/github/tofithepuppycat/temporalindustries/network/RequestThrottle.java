package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player minimum spacing between requests of one kind, for client-triggered packets whose
 * handling is expensive on the server.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID)
public final class RequestThrottle {
    public static final ResourceLocation TIMELINE_PREVIEW =
            ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_preview");
    public static final ResourceLocation CHRONOSPHERE_MAP =
            ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_map");

    private static final Map<UUID, Map<ResourceLocation, Long>> LAST_ALLOWED = new HashMap<>();

    private RequestThrottle() {}

    /**
     * Whether player may make a request of this kind now; if so, the request counts as made.
     */
    public static boolean tryAcquire(ServerPlayer player, ResourceLocation kind, int minIntervalTicks) {
        long now = player.level().getGameTime();
        Map<ResourceLocation, Long> byKind = LAST_ALLOWED.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        Long last = byKind.get(kind);
        if (last != null && now >= last && now - last < minIntervalTicks) return false;
        byKind.put(kind, now);
        return true;
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ALLOWED.remove(event.getEntity().getUUID());
    }
}
