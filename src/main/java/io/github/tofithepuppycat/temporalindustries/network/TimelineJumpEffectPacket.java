package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.TimelineJumpClientHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: a timeline machine (Chronosphere, Chronovault or Chronodial) just jumped to a
 * different commit at pos; spawns a swirling particle burst on everyone tracking that chunk.
 * intoFuture picks the chaos/order color and swirl direction to match {@link
 * io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity}'s
 * entropy shift. */
public class TimelineJumpEffectPacket implements CustomPacketPayload {
    public static final Type<TimelineJumpEffectPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_jump_effect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineJumpEffectPacket> STREAM_CODEC =
            StreamCodec.of(TimelineJumpEffectPacket::encode, TimelineJumpEffectPacket::decode);

    private final BlockPos pos;
    private final boolean intoFuture;

    public TimelineJumpEffectPacket(BlockPos pos, boolean intoFuture) {
        this.pos = pos;
        this.intoFuture = intoFuture;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineJumpEffectPacket packet) {
        buf.writeBlockPos(packet.pos);
        buf.writeBoolean(packet.intoFuture);
    }

    public static TimelineJumpEffectPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelineJumpEffectPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(TimelineJumpEffectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> TimelineJumpClientHandler.onJumpEffect(packet.pos, packet.intoFuture));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
