package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Client -> server: periodic poll for a Chronosphere's map state (which chunks are claimed/blocked)
 * and the current jump-cost preview, so the GUI stays live while open. */
public class ChronosphereStateRequestPacket implements CustomPacketPayload {
    public static final Type<ChronosphereStateRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_state_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereStateRequestPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereStateRequestPacket::encode, ChronosphereStateRequestPacket::decode);

    private final BlockPos machinePos;

    public ChronosphereStateRequestPacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereStateRequestPacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static ChronosphereStateRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereStateRequestPacket(buf.readBlockPos());
    }

    public static void handle(ChronosphereStateRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof ChronosphereMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof ChronosphereBlockEntity machine)) return;

            sendStateSync(sender, machine, packet.machinePos);
        });
    }

    /** Builds and sends a full state snapshot for machine to sender. Shared by the periodic poll
     * and by ChronosphereToggleChunkPacket, which wants an immediate refresh after a click. */
    public static void sendStateSync(ServerPlayer sender, ChronosphereBlockEntity machine, BlockPos machinePos) {
        Set<Long> selected = new HashSet<>();
        for (ChunkPos pos : machine.getAllChunks()) selected.add(pos.toLong());

        TemporalWorldData worldData = TemporalWorldData.get(sender.serverLevel().getServer());
        ResourceLocation dimension = sender.serverLevel().dimension().location();
        ChunkPos home = machine.getHomeChunkPos();
        List<Long> blocked = new ArrayList<>();
        int radius = ChronosphereBlockEntity.MAX_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!ChronosphereBlockEntity.isWithinRadius(dx, dz)) continue;
                ChunkPos pos = new ChunkPos(home.x + dx, home.z + dz);
                long key = pos.toLong();
                if (!selected.contains(key) && worldData.isTracked(dimension, pos)) {
                    blocked.add(key);
                }
            }
        }

        // How many of the claimed chunks are actually being tracked (recording deltas) right now —
        // may be fewer than selected.size() if auto-tracking is off, and can differ from it even
        // when auto-tracking is on if another owner (a held Portable ChronoMarker, another
        // machine) also happens to be tracking one of these chunks.
        int trackedCount = 0;
        for (long key : selected) {
            if (worldData.isTracked(dimension, new ChunkPos(key))) trackedCount++;
        }

        PacketDistributor.sendToPlayer(sender, new ChronosphereStateSyncPacket(
                machinePos, new ArrayList<>(selected), blocked, machine.isAutoTrackingEnabled(), trackedCount));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
