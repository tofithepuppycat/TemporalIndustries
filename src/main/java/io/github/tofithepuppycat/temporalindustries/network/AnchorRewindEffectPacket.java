package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.AnchorClientHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client notification broadcast to everyone tracking a player when their Temporal
 * Anchor rewinds them on death. Replaces vanilla's totem-of-undying entity event (id 35) so the
 * particle color and the item shown in the activation animation can be Temporal Anchor themed
 * instead of hardcoded to the totem.
 */
public class AnchorRewindEffectPacket implements CustomPacketPayload {
    public static final Type<AnchorRewindEffectPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "anchor_rewind_effect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnchorRewindEffectPacket> STREAM_CODEC =
            StreamCodec.of(AnchorRewindEffectPacket::encode, AnchorRewindEffectPacket::decode);

    private final int entityId;

    public AnchorRewindEffectPacket(int entityId) {
        this.entityId = entityId;
    }

    public static void encode(RegistryFriendlyByteBuf buf, AnchorRewindEffectPacket packet) {
        buf.writeVarInt(packet.entityId);
    }

    public static AnchorRewindEffectPacket decode(RegistryFriendlyByteBuf buf) {
        return new AnchorRewindEffectPacket(buf.readVarInt());
    }

    public static void handle(AnchorRewindEffectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> AnchorClientHandler.onRewindEffect(packet.entityId));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
