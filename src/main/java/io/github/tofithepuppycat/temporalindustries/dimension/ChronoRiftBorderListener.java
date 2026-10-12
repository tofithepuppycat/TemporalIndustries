package io.github.tofithepuppycat.temporalindustries.dimension;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stops players from wandering out of the Chrono Rift's reconstructed terrain: every server tick,
 * any player standing in an untracked chunk is snapped back to the last tracked-chunk position they
 * were seen at, the same way vanilla's world border pushes players back inward.
 */
public final class ChronoRiftBorderListener {
    private static final Map<UUID, Vec3> lastTrackedPos = new HashMap<>();

    private ChronoRiftBorderListener() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel riftLevel = ChronoRiftDimension.getLevel(server);
        if (riftLevel == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        for (ServerPlayer player : riftLevel.players()) {
            ChunkPos pos = new ChunkPos(player.blockPosition());
            if (data.isChronoRiftChunkTracked(pos)) {
                lastTrackedPos.put(player.getUUID(), player.position());
                continue;
            }

            Vec3 safe = lastTrackedPos.get(player.getUUID());
            if (safe == null) continue; // nowhere known-safe to send them back to yet
            player.teleportTo(safe.x, safe.y, safe.z);
            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        lastTrackedPos.remove(event.getEntity().getUUID());
    }
}
