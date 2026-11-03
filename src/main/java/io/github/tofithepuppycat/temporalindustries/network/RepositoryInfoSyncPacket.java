package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * Server -> client: what the free repository named {@code query} holds, or that there is none or it is taken, for
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
    private final boolean unavailable;
    @Nullable private final GlobalPos owner;

    public RepositoryInfoSyncPacket(String query, @Nullable TemporalWorldData.RepositoryStats stats, boolean unavailable,
                                    @Nullable GlobalPos owner) {
        this.query = query;
        this.stats = stats;
        this.unavailable = unavailable;
        this.owner = owner;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RepositoryInfoSyncPacket packet) {
        buf.writeUtf(packet.query, MAX_STRING_BYTES);
        buf.writeBoolean(packet.unavailable);
        buf.writeBoolean(packet.owner != null);
        if (packet.owner != null) {
            buf.writeResourceLocation(packet.owner.dimension().location());
            buf.writeBlockPos(packet.owner.pos());
        }
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
        boolean unavailable = buf.readBoolean();
        GlobalPos owner = null;
        if (buf.readBoolean()) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
            owner = GlobalPos.of(dimension, buf.readBlockPos());
        }
        if (!buf.readBoolean()) return new RepositoryInfoSyncPacket(query, null, unavailable, owner);
        return new RepositoryInfoSyncPacket(query, new TemporalWorldData.RepositoryStats(
                buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_STRING_BYTES), buf.readVarInt(), buf.readLong()), unavailable, owner);
    }

    public static void handle(RepositoryInfoSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ChronoRemoteClientState.updateRepositoryInfo(packet.query, packet.stats, packet.unavailable, packet.owner));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
