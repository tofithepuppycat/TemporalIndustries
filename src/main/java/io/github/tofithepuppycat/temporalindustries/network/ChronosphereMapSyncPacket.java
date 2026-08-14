package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereMapClientState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server -> client: terrain thumbnails (see {@link io.github.tofithepuppycat.temporalindustries.chronomap.ChronoMapSampler})
 * for every currently-loaded chunk in a Chronosphere's claimable area, for {@link ChronosphereMapClientState}. */
public class ChronosphereMapSyncPacket implements CustomPacketPayload {
    public static final Type<ChronosphereMapSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_map_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereMapSyncPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereMapSyncPacket::encode, ChronosphereMapSyncPacket::decode);

    private final BlockPos machinePos;
    private final Map<Long, byte[]> thumbnails;

    public ChronosphereMapSyncPacket(BlockPos machinePos, Map<Long, byte[]> thumbnails) {
        this.machinePos = machinePos;
        this.thumbnails = thumbnails;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereMapSyncPacket packet) {
        buf.writeBlockPos(packet.machinePos);

        // Thumbnails are cheap to compute but not cheap to send raw (up to 25 chunks x 256 bytes
        // each): deflate the same way TimelinePreviewSyncPacket does for its commit payload.
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

    public static ChronosphereMapSyncPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos machinePos = buf.readBlockPos();

        int rawBodyLength = buf.readVarInt();
        byte[] compressed = buf.readByteArray();
        byte[] rawBody = CompressionUtil.decompress(compressed);
        if (rawBody.length != rawBodyLength) {
            throw new IllegalStateException("Decompressed Chronosphere map payload size mismatch: expected "
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

        return new ChronosphereMapSyncPacket(machinePos, thumbnails);
    }

    public static void handle(ChronosphereMapSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ChronosphereMapClientState.updateFromServer(packet.machinePos, packet.thumbnails));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
