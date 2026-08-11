package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: jump a timeline-view machine (Time Machine or Chronosphere) to targetGameTime. */
public class RollbackChunkPacket implements CustomPacketPayload {
    public static final Type<RollbackChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "rollback_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RollbackChunkPacket> STREAM_CODEC =
            StreamCodec.of(RollbackChunkPacket::encode, RollbackChunkPacket::decode);

    private final BlockPos machinePos;
    private final long targetGameTime;

    public RollbackChunkPacket(BlockPos machinePos, long targetGameTime) {
        this.machinePos = machinePos;
        this.targetGameTime = targetGameTime;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RollbackChunkPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.targetGameTime);
    }

    public static RollbackChunkPacket decode(RegistryFriendlyByteBuf buf) {
        return new RollbackChunkPacket(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(RollbackChunkPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) {
                return;
            }

            if (!menu.getBlockPos().equals(packet.machinePos)) {
                return;
            }

            if (sender.distanceToSqr(packet.machinePos.getX() + 0.5D, packet.machinePos.getY() + 0.5D, packet.machinePos.getZ() + 0.5D) > 64.0D) {
                return;
            }

            menu.getTimelineProvider().jump(packet.targetGameTime);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
