package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.LootTableSuggestionsClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server -> client: the full list of registered loot table ids, in reply to a suggestions request. */
public class LootTableSuggestionsSyncPacket implements CustomPacketPayload {
    public static final Type<LootTableSuggestionsSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "loot_table_suggestions_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LootTableSuggestionsSyncPacket> STREAM_CODEC =
            StreamCodec.of(LootTableSuggestionsSyncPacket::encode, LootTableSuggestionsSyncPacket::decode);

    private final List<ResourceLocation> lootTables;

    public LootTableSuggestionsSyncPacket(List<ResourceLocation> lootTables) {
        this.lootTables = lootTables;
    }

    public static void encode(RegistryFriendlyByteBuf buf, LootTableSuggestionsSyncPacket packet) {
        buf.writeVarInt(packet.lootTables.size());
        for (ResourceLocation id : packet.lootTables) {
            buf.writeResourceLocation(id);
        }
    }

    public static LootTableSuggestionsSyncPacket decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ResourceLocation> lootTables = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lootTables.add(buf.readResourceLocation());
        }
        return new LootTableSuggestionsSyncPacket(lootTables);
    }

    public static void handle(LootTableSuggestionsSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> LootTableSuggestionsClientState.updateFromServer(packet.lootTables));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
