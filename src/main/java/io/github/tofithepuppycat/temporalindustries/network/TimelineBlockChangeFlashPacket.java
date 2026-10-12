package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.TimelineJumpClientHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: a batch of block positions that were just rewritten by a timeline jump, so the
 * client can flash each one instead of letting the rewrite pop in silently.
 */
public class TimelineBlockChangeFlashPacket implements CustomPacketPayload {
    public static final Type<TimelineBlockChangeFlashPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_block_change_flash"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineBlockChangeFlashPacket> STREAM_CODEC =
            StreamCodec.of(TimelineBlockChangeFlashPacket::encode, TimelineBlockChangeFlashPacket::decode);

    private final List<BlockPos> positions;
    private final boolean intoFuture;

    public TimelineBlockChangeFlashPacket(List<BlockPos> positions, boolean intoFuture) {
        this.positions = positions;
        this.intoFuture = intoFuture;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineBlockChangeFlashPacket packet) {
        buf.writeVarInt(packet.positions.size());
        for (BlockPos pos : packet.positions) {
            buf.writeBlockPos(pos);
        }
        buf.writeBoolean(packet.intoFuture);
    }

    public static TimelineBlockChangeFlashPacket decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BlockPos> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            positions.add(buf.readBlockPos());
        }
        boolean intoFuture = buf.readBoolean();
        return new TimelineBlockChangeFlashPacket(positions, intoFuture);
    }

    public static void handle(TimelineBlockChangeFlashPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> TimelineJumpClientHandler.onBlockChangeFlash(packet.positions, packet.intoFuture));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
