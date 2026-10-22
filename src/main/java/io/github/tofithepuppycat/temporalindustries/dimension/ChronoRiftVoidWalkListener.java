package io.github.tofithepuppycat.temporalindustries.dimension;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * Lets players fly through untracked Chrono Rift chunks (those with no history at the selected
 * moment) by granting flight while they are inside one.
 */
public final class ChronoRiftVoidWalkListener {
    /** Players whose flight this listener granted. */
    private static final Set<UUID> granted = new HashSet<>();

    /** Players arriving from the rift who take no fall damage until they next touch down. */
    private static final Set<UUID> fallProtected = new HashSet<>();

    private ChronoRiftVoidWalkListener() {}

    /** Makes player immune to fall damage until they next land (or enter water). */
    public static void protectFromFall(ServerPlayer player) {
        player.resetFallDistance();
        // The ground flag carried over from the departure point would otherwise end protection on the next tick.
        player.setOnGround(false);
        fallProtected.add(player.getUUID());
    }

    public static void onLivingFall(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && fallProtected.remove(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        fallProtected.removeIf(id -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            return player == null || player.onGround() || player.isInWater();
        });

        ServerLevel riftLevel = ChronoRiftDimension.getLevel(server);
        if (riftLevel == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        for (ServerPlayer player : riftLevel.players()) {
            if (data.isChronoRiftChunkTracked(new ChunkPos(player.blockPosition()))) {
                revoke(player);
            } else if (!player.getAbilities().mayfly) {
                granted.add(player.getUUID());
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }
        }

        Iterator<UUID> it = granted.iterator();
        while (it.hasNext()) {
            ServerPlayer player = server.getPlayerList().getPlayer(it.next());
            if (player == null) {
                it.remove();
            } else if (player.level() != riftLevel) {
                it.remove();
                restore(player);
            }
        }
    }

    private static void revoke(ServerPlayer player) {
        if (granted.remove(player.getUUID())) restore(player);
    }

    private static void restore(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) return;
        player.getAbilities().mayfly = false;
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) revoke(player);
        fallProtected.remove(event.getEntity().getUUID());
        granted.remove(event.getEntity().getUUID());
    }
}
