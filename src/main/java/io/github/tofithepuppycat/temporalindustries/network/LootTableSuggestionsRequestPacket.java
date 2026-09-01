package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Client -> server: request every currently-registered loot table id, for the Loot Generator
 * screen's search-bar autocomplete — sent once when the screen opens, since the client has no
 * access to the server's reloadable loot table registry otherwise. No payload. */
public class LootTableSuggestionsRequestPacket implements CustomPacketPayload {
    public static final Type<LootTableSuggestionsRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "loot_table_suggestions_request"));

    public static final LootTableSuggestionsRequestPacket INSTANCE = new LootTableSuggestionsRequestPacket();

    public static final StreamCodec<RegistryFriendlyByteBuf, LootTableSuggestionsRequestPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    public static void handle(LootTableSuggestionsRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            List<ResourceLocation> ids = new ArrayList<>();
            for (ResourceLocation id : sender.server.reloadableRegistries().getKeys(Registries.LOOT_TABLE)) {
                if (LootGeneratorBlockEntity.isChestLootTable(id)) {
                    ids.add(id);
                }
            }
            PacketDistributor.sendToPlayer(sender, new LootTableSuggestionsSyncPacket(ids));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
