package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: request the glued-region list for the player's current dimension — sent once
 * when a Temporal Glue is selected and periodically while it stays selected, since another player
 * may glue/unglue an area while this one is looking at it. No payload: the server derives the
 * dimension from the requesting player. */
public class GlueRegionRequestPacket implements CustomPacketPayload {
    public static final Type<GlueRegionRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "glue_region_request"));

    public static final GlueRegionRequestPacket INSTANCE = new GlueRegionRequestPacket();

    public static final StreamCodec<RegistryFriendlyByteBuf, GlueRegionRequestPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    public static void handle(GlueRegionRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            ResourceLocation dimension = sender.level().dimension().location();
            TemporalWorldData worldData = TemporalWorldData.get(sender.getServer());
            PacketDistributor.sendToPlayer(sender,
                    new GlueRegionSyncPacket(dimension, worldData.getGluedRegions(dimension)));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
