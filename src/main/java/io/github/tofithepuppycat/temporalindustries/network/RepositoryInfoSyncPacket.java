package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * Server -> client: what the free repository named {@code query} holds, or that there is none, for
 * the Chrono Remote Control's repository picker.
 */
public class RepositoryInfoSyncPacket implements CustomPacketPayload {
    public static final Type<RepositoryInfoSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "repository_info_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RepositoryInfoSyncPacket> STREAM_CODEC =
            StreamCodec.of(RepositoryInfoSyncPacket::encode, RepositoryInfoSyncPacket::decode);

    private static final int MAX_STRING_BYTES = TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH * 4;

    private final String query;
    @Nullable private final TemporalWorldData.RepositoryStats stats;

    public RepositoryInfoSyncPacket(String query, @Nullable TemporalWorldData.RepositoryStats stats) {
        this.query = query;
        this.stats = stats;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RepositoryInfoSyncPacket packet) {
        buf.writeUtf(packet.query, MAX_STRING_BYTES);
        buf.writeBoolean(packet.stats != null);
        if (packet.stats == null) return;
        buf.writeVarInt(packet.stats.commits());
        buf.writeVarInt(packet.stats.branches());
        buf.writeUtf(packet.stats.currentBranch(), MAX_STRING_BYTES);
        buf.writeVarInt(packet.stats.chunks());
        buf.writeLong(packet.stats.lastCommitTime());
    }

    public static RepositoryInfoSyncPacket decode(RegistryFriendlyByteBuf buf) {
        String query = buf.readUtf(MAX_STRING_BYTES);
        if (!buf.readBoolean()) return new RepositoryInfoSyncPacket(query, null);
        return new RepositoryInfoSyncPacket(query, new TemporalWorldData.RepositoryStats(
                buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_STRING_BYTES), buf.readVarInt(), buf.readLong()));
    }

    public static void handle(RepositoryInfoSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ChronoRemoteClientState.updateRepositoryInfo(packet.query, packet.stats));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
