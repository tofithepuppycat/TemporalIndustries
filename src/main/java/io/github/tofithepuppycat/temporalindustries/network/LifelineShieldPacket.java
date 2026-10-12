package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.LifelineShieldClientHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server -> client: a Temporal Lifeline just cancelled an explosion's block damage. */
public class LifelineShieldPacket implements CustomPacketPayload {
    public static final Type<LifelineShieldPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "lifeline_shield"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LifelineShieldPacket> STREAM_CODEC =
            StreamCodec.of(LifelineShieldPacket::encode, LifelineShieldPacket::decode);

    /** One beam from a lifeline to a block it saved. */
    public record Beam(BlockPos lifeline, BlockPos target) {}

    private final Vec3 center;
    private final float radius;
    private final List<Beam> beams;

    public LifelineShieldPacket(Vec3 center, float radius, List<Beam> beams) {
        this.center = center;
        this.radius = radius;
        this.beams = beams;
    }

    public static void encode(RegistryFriendlyByteBuf buf, LifelineShieldPacket packet) {
        buf.writeDouble(packet.center.x);
        buf.writeDouble(packet.center.y);
        buf.writeDouble(packet.center.z);
        buf.writeFloat(packet.radius);
        buf.writeVarInt(packet.beams.size());
        for (Beam beam : packet.beams) {
            buf.writeBlockPos(beam.lifeline());
            buf.writeBlockPos(beam.target());
        }
    }

    public static LifelineShieldPacket decode(RegistryFriendlyByteBuf buf) {
        Vec3 center = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float radius = buf.readFloat();
        int count = buf.readVarInt();
        List<Beam> beams = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            beams.add(new Beam(buf.readBlockPos(), buf.readBlockPos()));
        }
        return new LifelineShieldPacket(center, radius, beams);
    }

    public static void handle(LifelineShieldPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> LifelineShieldClientHandler.onShield(packet.center, packet.radius, packet.beams));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
