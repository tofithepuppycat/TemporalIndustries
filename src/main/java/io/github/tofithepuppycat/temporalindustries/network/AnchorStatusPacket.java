package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.AnchorClientHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client notification sent when a Temporal Anchor reverts a player's timeline after
 * death. Carries the corrected hunger values (vanilla has no generic resync packet for hunger,
 * since the client predicts it locally) plus enough context to show a "timeline restored" message.
 */
public class AnchorStatusPacket implements CustomPacketPayload {
    public static final Type<AnchorStatusPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "anchor_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnchorStatusPacket> STREAM_CODEC =
            StreamCodec.of(AnchorStatusPacket::encode, AnchorStatusPacket::decode);

    private final int revertedChangeCount;
    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;

    public AnchorStatusPacket(int revertedChangeCount, int foodLevel, float saturation, float exhaustion) {
        this.revertedChangeCount = revertedChangeCount;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
    }

    public static void encode(RegistryFriendlyByteBuf buf, AnchorStatusPacket packet) {
        buf.writeVarInt(packet.revertedChangeCount);
        buf.writeVarInt(packet.foodLevel);
        buf.writeFloat(packet.saturation);
        buf.writeFloat(packet.exhaustion);
    }

    public static AnchorStatusPacket decode(RegistryFriendlyByteBuf buf) {
        return new AnchorStatusPacket(buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(AnchorStatusPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> AnchorClientHandler.onReverted(
                packet.revertedChangeCount, packet.foodLevel, packet.saturation, packet.exhaustion));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
