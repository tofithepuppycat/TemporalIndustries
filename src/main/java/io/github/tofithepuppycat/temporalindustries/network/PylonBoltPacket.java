package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.PylonBoltRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client: a pylon is moving something along the link from {@code from} to {@code to};
 * shows (or refreshes) a lightning bolt of {@code color} along it.
 */
public class PylonBoltPacket implements CustomPacketPayload {
    public static final Type<PylonBoltPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "pylon_bolt"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PylonBoltPacket> STREAM_CODEC =
            StreamCodec.of(PylonBoltPacket::encode, PylonBoltPacket::decode);

    private final BlockPos from;
    private final BlockPos to;
    private final int color;

    public PylonBoltPacket(BlockPos from, BlockPos to, int color) {
        this.from = from;
        this.to = to;
        this.color = color;
    }

    public static void encode(RegistryFriendlyByteBuf buf, PylonBoltPacket packet) {
        buf.writeBlockPos(packet.from);
        buf.writeBlockPos(packet.to);
        buf.writeInt(packet.color);
    }

    public static PylonBoltPacket decode(RegistryFriendlyByteBuf buf) {
        return new PylonBoltPacket(buf.readBlockPos(), buf.readBlockPos(), buf.readInt());
    }

    public static void handle(PylonBoltPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> PylonBoltRenderer.add(packet.from, packet.to, packet.color));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
