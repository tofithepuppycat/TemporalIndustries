package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Awards "Grand...child paradox?" when a player dies to their own Echo, whether it hit them
 * directly or through something it threw, shot or set off.
 */
public final class EchoKillListener {
    private static final ResourceLocation ADVANCEMENT_ID =
            ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "killed_by_your_echo");

    private EchoKillListener() {}

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof EchoPlayer echo)) return;
        if (!victim.getUUID().equals(echo.getRecordingOwner())) return;

        MinecraftServer server = victim.getServer();
        AdvancementHolder advancement = server != null ? server.getAdvancements().get(ADVANCEMENT_ID) : null;
        if (advancement != null) {
            victim.getAdvancements().award(advancement, "killed_by_echo");
        }
    }
}
