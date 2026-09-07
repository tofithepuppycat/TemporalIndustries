package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChronoMapSampler;
import io.github.tofithepuppycat.temporalindustries.item.PortableChronoMarkerItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Client -> server: request terrain thumbnails for the Portable Chrono Marker's area-select map,
 * keyed by an arbitrary anchor chunk (the player's position when they opened the screen) since the
 * marker has no block/menu to validate against; trust is anchored to the sender's current position. */
public class ChronoMarkerMapRequestPacket implements CustomPacketPayload {
    public static final Type<ChronoMarkerMapRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_marker_map_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoMarkerMapRequestPacket> STREAM_CODEC =
            StreamCodec.of(ChronoMarkerMapRequestPacket::encode, ChronoMarkerMapRequestPacket::decode);

    /** Slack (in chunks) allowed between the claimed anchor and the sender's actual chunk. */
    private static final int ANCHOR_SLACK_CHUNKS = 4;

    private final long anchorKey;

    public ChronoMarkerMapRequestPacket(long anchorKey) {
        this.anchorKey = anchorKey;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoMarkerMapRequestPacket packet) {
        buf.writeLong(packet.anchorKey);
    }

    public static ChronoMarkerMapRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronoMarkerMapRequestPacket(buf.readLong());
    }

    public static void handle(ChronoMarkerMapRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            ChunkPos anchor = new ChunkPos(packet.anchorKey);
            ChunkPos playerChunk = new ChunkPos(sender.blockPosition());
            int radius = PortableChronoMarkerItem.MAP_RADIUS_CHUNKS;
            int maxOffset = radius + ANCHOR_SLACK_CHUNKS;
            if (Math.abs(anchor.x - playerChunk.x) > maxOffset || Math.abs(anchor.z - playerChunk.z) > maxOffset) return;

            Map<Long, byte[]> thumbnails = new LinkedHashMap<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!PortableChronoMarkerItem.MAP_SHAPE.contains(radius, dx, dz)) continue;
                    ChunkPos pos = new ChunkPos(anchor.x + dx, anchor.z + dz);
                    // Only sample already-loaded chunks; don't force generation for a preview.
                    if (!sender.level().hasChunk(pos.x, pos.z)) continue;
                    thumbnails.put(pos.toLong(), ChronoMapSampler.sampleChunk(sender.serverLevel(), pos));
                }
            }

            PacketDistributor.sendToPlayer(sender, new ChronoMarkerMapSyncPacket(packet.anchorKey, thumbnails));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
