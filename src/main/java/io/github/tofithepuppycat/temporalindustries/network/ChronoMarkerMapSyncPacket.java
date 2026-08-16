package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChunkThumbnailClientState;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server -> client: terrain thumbnails for the Portable Chrono Marker's area-select map — see
 * {@link ChronoMarkerMapRequestPacket}. Mirrors {@link ChronosphereMapSyncPacket}'s wire format
 * (deflated payload, same reasoning) but keyed by a generic anchor chunk key rather than a
 * machine's BlockPos. */
public class ChronoMarkerMapSyncPacket implements CustomPacketPayload {
    public static final Type<ChronoMarkerMapSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_marker_map_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoMarkerMapSyncPacket> STREAM_CODEC =
            StreamCodec.of(ChronoMarkerMapSyncPacket::encode, ChronoMarkerMapSyncPacket::decode);

    private final long anchorKey;
    private final Map<Long, byte[]> thumbnails;

    public ChronoMarkerMapSyncPacket(long anchorKey, Map<Long, byte[]> thumbnails) {
        this.anchorKey = anchorKey;
        this.thumbnails = thumbnails;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoMarkerMapSyncPacket packet) {
        buf.writeLong(packet.anchorKey);

        // Thumbnails are cheap to compute but not cheap to send raw: deflate the same way
        // ChronosphereMapSyncPacket does for its own thumbnail payload.
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        body.writeVarInt(packet.thumbnails.size());
        for (Map.Entry<Long, byte[]> entry : packet.thumbnails.entrySet()) {
            body.writeLong(entry.getKey());
            body.writeByteArray(entry.getValue());
        }
        byte[] rawBody = new byte[body.readableBytes()];
        body.readBytes(rawBody);
        body.release();

        buf.writeVarInt(rawBody.length);
        buf.writeByteArray(CompressionUtil.compress(rawBody));
    }

    public static ChronoMarkerMapSyncPacket decode(RegistryFriendlyByteBuf buf) {
        long anchorKey = buf.readLong();

        int rawBodyLength = buf.readVarInt();
        byte[] compressed = buf.readByteArray();
        byte[] rawBody = CompressionUtil.decompress(compressed);
        if (rawBody.length != rawBodyLength) {
            throw new IllegalStateException("Decompressed Chrono Marker map payload size mismatch: expected "
                    + rawBodyLength + " but got " + rawBody.length);
        }
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.wrappedBuffer(rawBody));
        int count = body.readVarInt();
        Map<Long, byte[]> thumbnails = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            long key = body.readLong();
            thumbnails.put(key, body.readByteArray());
        }
        body.release();

        return new ChronoMarkerMapSyncPacket(anchorKey, thumbnails);
    }

    public static void handle(ChronoMarkerMapSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ChunkThumbnailClientState.updateFromServer(packet.anchorKey, packet.thumbnails));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
