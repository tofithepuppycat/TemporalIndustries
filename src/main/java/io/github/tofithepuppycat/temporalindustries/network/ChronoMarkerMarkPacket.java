package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChunkArea;
import io.github.tofithepuppycat.temporalindustries.item.PortableChronoMarkerItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Client -> server: confirm the Portable Chrono Marker's area-select map, marking every chunk the
 * player selected (see {@link io.github.tofithepuppycat.temporalindustries.client.screen.ChronoMarkerMapScreen}).
 * Every selected chunk is re-validated against the sender's CURRENT chunk position, not whatever
 * anchor the client claims — nothing beyond "here are some chunk coordinates" is trusted from the
 * client, so if the player walked away from the anchor before confirming, chunks that fell out of
 * range are simply dropped rather than marked. */
public class ChronoMarkerMarkPacket implements CustomPacketPayload {
    public static final Type<ChronoMarkerMarkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_marker_mark"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoMarkerMarkPacket> STREAM_CODEC =
            StreamCodec.of(ChronoMarkerMarkPacket::encode, ChronoMarkerMarkPacket::decode);

    private final List<Long> chunkKeys;

    public ChronoMarkerMarkPacket(List<Long> chunkKeys) {
        this.chunkKeys = chunkKeys;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoMarkerMarkPacket packet) {
        buf.writeVarInt(packet.chunkKeys.size());
        for (long key : packet.chunkKeys) buf.writeLong(key);
    }

    public static ChronoMarkerMarkPacket decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Long> chunkKeys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) chunkKeys.add(buf.readLong());
        return new ChronoMarkerMarkPacket(chunkKeys);
    }

    public static void handle(ChronoMarkerMarkPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.level() instanceof ServerLevel serverLevel)) return;

            ChunkPos playerChunk = new ChunkPos(sender.blockPosition());
            int radius = PortableChronoMarkerItem.MAP_RADIUS_CHUNKS;
            List<ChunkPos> chunks = new ArrayList<>();
            for (long key : packet.chunkKeys) {
                ChunkPos pos = new ChunkPos(key);
                if (!ChunkArea.isWithinRadius(radius, pos.x - playerChunk.x, pos.z - playerChunk.z)) continue;
                chunks.add(pos);
            }
            if (chunks.isEmpty()) return;

            PortableChronoMarkerItem.recordMark(sender, serverLevel, chunks);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
