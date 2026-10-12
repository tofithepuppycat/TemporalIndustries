package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: the repository names matching a {@link RepositorySuggestRequestPacket}'s query,
 * for {@link ChronosphereClientState}.
 */
public class RepositorySuggestSyncPacket implements CustomPacketPayload {
    public static final Type<RepositorySuggestSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "repository_suggest_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RepositorySuggestSyncPacket> STREAM_CODEC =
            StreamCodec.of(RepositorySuggestSyncPacket::encode, RepositorySuggestSyncPacket::decode);

    private static final int MAX_STRING_BYTES = TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH * 4;

    private final String query;
    private final List<String> names;

    public RepositorySuggestSyncPacket(String query, List<String> names) {
        this.query = query;
        this.names = names;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RepositorySuggestSyncPacket packet) {
        buf.writeUtf(packet.query, MAX_STRING_BYTES);
        buf.writeVarInt(packet.names.size());
        for (String name : packet.names) buf.writeUtf(name, MAX_STRING_BYTES);
    }

    public static RepositorySuggestSyncPacket decode(RegistryFriendlyByteBuf buf) {
        String query = buf.readUtf(MAX_STRING_BYTES);
        int count = buf.readVarInt();
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) names.add(buf.readUtf(MAX_STRING_BYTES));
        return new RepositorySuggestSyncPacket(query, names);
    }

    public static void handle(RepositorySuggestSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ChronosphereClientState.updateSuggestions(packet.query, packet.names));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
