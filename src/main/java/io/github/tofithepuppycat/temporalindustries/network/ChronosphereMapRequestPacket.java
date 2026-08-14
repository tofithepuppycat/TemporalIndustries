package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChronoMapSampler;
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

import java.util.LinkedHashMap;
import java.util.Map;

/** Client -> server: request terrain thumbnails for every claimable cell in a Chronosphere's map —
 * sent once when the map overlay opens and periodically while it stays open, since the underlying
 * terrain can change (players building, explosions, growth) while the player is looking at it. */
public class ChronosphereMapRequestPacket implements CustomPacketPayload {
    public static final Type<ChronosphereMapRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_map_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereMapRequestPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereMapRequestPacket::encode, ChronosphereMapRequestPacket::decode);

    private final BlockPos machinePos;

    public ChronosphereMapRequestPacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereMapRequestPacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static ChronosphereMapRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereMapRequestPacket(buf.readBlockPos());
    }

    public static void handle(ChronosphereMapRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof ChronosphereMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof ChronosphereBlockEntity machine)) return;

            ChunkPos home = machine.getHomeChunkPos();
            int radius = ChronosphereBlockEntity.MAX_RADIUS;
            Map<Long, byte[]> thumbnails = new LinkedHashMap<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!ChronosphereBlockEntity.isWithinRadius(dx, dz)) continue;
                    ChunkPos pos = new ChunkPos(home.x + dx, home.z + dz);
                    // Only sample chunks already loaded — this is a preview, not a reason to force
                    // remote/unclaimed chunks to generate.
                    if (!sender.level().hasChunk(pos.x, pos.z)) continue;
                    thumbnails.put(pos.toLong(), ChronoMapSampler.sampleChunk(sender.serverLevel(), pos));
                }
            }

            PacketDistributor.sendToPlayer(sender, new ChronosphereMapSyncPacket(packet.machinePos, thumbnails));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
