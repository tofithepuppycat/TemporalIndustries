package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Server -> client copy of the resolved item energy costs, which are only computed server-side but
 * are needed by the client to show jump cost tooltips.
 */
public class ItemCostSyncPacket implements CustomPacketPayload {
    public static final Type<ItemCostSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "item_cost_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemCostSyncPacket> STREAM_CODEC =
            StreamCodec.of(ItemCostSyncPacket::encode, ItemCostSyncPacket::decode);

    private final Map<ResourceLocation, Integer> costs;

    public ItemCostSyncPacket(Map<ResourceLocation, Integer> costs) {
        this.costs = costs;
    }

    public static ItemCostSyncPacket fromResolved() {
        Map<ResourceLocation, Integer> costs = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : ItemEnergyCosts.snapshot().entrySet()) {
            costs.put(BuiltInRegistries.ITEM.getKey(entry.getKey()), entry.getValue());
        }
        return new ItemCostSyncPacket(costs);
    }

    public static void encode(RegistryFriendlyByteBuf buf, ItemCostSyncPacket packet) {
        buf.writeVarInt(packet.costs.size());
        for (Map.Entry<ResourceLocation, Integer> entry : packet.costs.entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public static ItemCostSyncPacket decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<ResourceLocation, Integer> costs = new HashMap<>();
        for (int i = 0; i < count; i++) {
            costs.put(buf.readResourceLocation(), buf.readVarInt());
        }
        return new ItemCostSyncPacket(costs);
    }

    public static void handle(ItemCostSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Map<Item, Integer> resolved = new HashMap<>();
            for (Map.Entry<ResourceLocation, Integer> entry : packet.costs.entrySet()) {
                BuiltInRegistries.ITEM.getOptional(entry.getKey()).ifPresent(item -> resolved.put(item, entry.getValue()));
            }
            ItemEnergyCosts.setResolved(resolved);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
