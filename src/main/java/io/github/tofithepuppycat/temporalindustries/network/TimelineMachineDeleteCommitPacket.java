package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: deletes a commit (and everything descended from it) from a timeline machine's
 * repository, provided it isn't on the current head's ancestry.
 */
public class TimelineMachineDeleteCommitPacket implements CustomPacketPayload {
    public static final Type<TimelineMachineDeleteCommitPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_machine_delete_commit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineMachineDeleteCommitPacket> STREAM_CODEC =
            StreamCodec.of(TimelineMachineDeleteCommitPacket::encode, TimelineMachineDeleteCommitPacket::decode);

    private final BlockPos machinePos;
    private final long commitId;

    public TimelineMachineDeleteCommitPacket(BlockPos machinePos, long commitId) {
        this.machinePos = machinePos;
        this.commitId = commitId;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineMachineDeleteCommitPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.commitId);
    }

    public static TimelineMachineDeleteCommitPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelineMachineDeleteCommitPacket(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(TimelineMachineDeleteCommitPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(menu.getTimelineProvider() instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.deleteCommit(packet.commitId);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
