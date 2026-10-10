package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: jump a timeline-view machine (Chronovault or Chronosphere) to a specific
 * commit. The target time is read off the commit itself server-side, so the commit id is the only
 * thing that needs to travel. */
public class RollbackChunkPacket implements CustomPacketPayload {
    public static final Type<RollbackChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "rollback_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RollbackChunkPacket> STREAM_CODEC =
            StreamCodec.of(RollbackChunkPacket::encode, RollbackChunkPacket::decode);

    private final BlockPos machinePos;
    /** The exact commit the player clicked in the graph. */
    private final long targetCommitId;

    public RollbackChunkPacket(BlockPos machinePos, long targetCommitId) {
        this.machinePos = machinePos;
        this.targetCommitId = targetCommitId;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RollbackChunkPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.targetCommitId);
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

            TimelineViewProvider.JumpResult result = menu.getTimelineProvider().jump(packet.targetCommitId);
            if (result == TimelineViewProvider.JumpResult.INSUFFICIENT_ENERGY) {
                sender.displayClientMessage(Component.translatable("gui.temporalindustries.timeline_view.insufficient_energy"), true);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
