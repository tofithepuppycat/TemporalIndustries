package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChronoRiftClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server -> client: which Chrono Rift chunks (by chunk-key) currently have valid reconstructed
 * history ("tracked") vs none ("untracked"), sent on rift entry and again as chunks lazily fill.
 */
public class ChronoRiftChunkTrackedSyncPacket implements CustomPacketPayload {
    public static final Type<ChronoRiftChunkTrackedSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_rift_chunk_tracked_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoRiftChunkTrackedSyncPacket> STREAM_CODEC =
            StreamCodec.of(ChronoRiftChunkTrackedSyncPacket::encode, ChronoRiftChunkTrackedSyncPacket::decode);

    private final Map<Long, Boolean> updates;

    public ChronoRiftChunkTrackedSyncPacket(Map<Long, Boolean> updates) {
        this.updates = updates;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoRiftChunkTrackedSyncPacket packet) {
        buf.writeVarInt(packet.updates.size());
        for (Map.Entry<Long, Boolean> entry : packet.updates.entrySet()) {
            buf.writeLong(entry.getKey());
            buf.writeBoolean(entry.getValue());
        }
    }

    public static ChronoRiftChunkTrackedSyncPacket decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<Long, Boolean> updates = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            updates.put(buf.readLong(), buf.readBoolean());
        }
        return new ChronoRiftChunkTrackedSyncPacket(updates);
    }

    public static void handle(ChronoRiftChunkTrackedSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ChronoRiftClientState.applyUpdates(packet.updates));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
