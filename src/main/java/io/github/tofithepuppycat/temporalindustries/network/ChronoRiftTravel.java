package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftDimension;
import io.github.tofithepuppycat.temporalindustries.item.ChronoscopeItem;
import io.github.tofithepuppycat.temporalindustries.timeline.ChronoRiftFiller;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Moves a player into the Chrono Rift at a chosen moment. */
public final class ChronoRiftTravel {
    /**
     * Radius (in chunks) synchronously filled around the landing point before teleporting, so the
     * player never lands in unfilled void.
     */
    private static final int PREFILL_RADIUS_CHUNKS = 1;

    private ChronoRiftTravel() {}

    public static void enter(ServerPlayer sender, long selectedGameTime) {
        MinecraftServer server = sender.getServer();
        if (server == null || sender.level().dimension() == ChronoRiftDimension.LEVEL_KEY) return;

        ServerLevel riftLevel = ChronoRiftDimension.getLevel(server);
        if (riftLevel == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        if (!data.canEnterChronoRift(riftLevel, selectedGameTime)) {
            sender.displayClientMessage(Component.translatable("item.temporalindustries.chronoscope.rift_busy"), true);
            return;
        }
        if (riftLevel.players().isEmpty()) {
            data.setChronoRiftActiveTime(selectedGameTime);
        }

        PlayerTemporalState state = data.getOrCreatePlayerState(sender.getUUID());
        state.setChronoRiftOrigin(new PlayerTemporalState.ChronoRiftOrigin(
                sender.level().dimension().location(), sender.getX(), sender.getY(), sender.getZ(),
                sender.getYRot(), sender.getXRot()));
        data.setDirty();

        ChunkPos destChunk = new ChunkPos(sender.blockPosition());
        Map<Long, Boolean> trackedSnapshot = new LinkedHashMap<>();
        // Every chunk in the prefill radius is written before any of them is settled: settling
        // a chunk fires neighbor-shape/destroy checks that can reach into a sibling chunk still
        // in this same loop, and that sibling looks like unfilled void until its own turn comes
        // up — wrongly destroying supported blocks (e.g. crops) right at the boundary between
        // them.
        List<BlockPos> deferredSettle = new ArrayList<>();
        for (int dx = -PREFILL_RADIUS_CHUNKS; dx <= PREFILL_RADIUS_CHUNKS; dx++) {
            for (int dz = -PREFILL_RADIUS_CHUNKS; dz <= PREFILL_RADIUS_CHUNKS; dz++) {
                ChunkPos pos = new ChunkPos(destChunk.x + dx, destChunk.z + dz);
                riftLevel.getChunk(pos.x, pos.z); // force load/generate (pure void, cheap)
                ChronoRiftFiller.fillChunk(server, riftLevel, pos, selectedGameTime, deferredSettle);
                trackedSnapshot.put(pos.toLong(), data.isChronoRiftChunkTracked(pos));
            }
        }
        TemporalRepository.settleBlocks(riftLevel, deferredSettle);

        BlockPos target = ChronoscopeItem.findStandingSpace(riftLevel, sender.blockPosition());
        sender.teleportTo(riftLevel, sender.getX(), target.getY(), sender.getZ(), sender.getYRot(), sender.getXRot());
        PacketDistributor.sendToPlayer(sender, new ChronoRiftChunkTrackedSyncPacket(trackedSnapshot));
    }
}
