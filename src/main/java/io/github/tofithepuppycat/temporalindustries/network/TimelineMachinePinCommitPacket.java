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
 * Client -> server: pins a commit as the point a timeline machine returns to on a redstone trigger;
 * an id that names no commit clears the pin.
 */
public class TimelineMachinePinCommitPacket implements CustomPacketPayload {
    public static final Type<TimelineMachinePinCommitPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_machine_pin_commit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineMachinePinCommitPacket> STREAM_CODEC =
            StreamCodec.of(TimelineMachinePinCommitPacket::encode, TimelineMachinePinCommitPacket::decode);

    private final BlockPos machinePos;
    private final long commitId;

    public TimelineMachinePinCommitPacket(BlockPos machinePos, long commitId) {
        this.machinePos = machinePos;
        this.commitId = commitId;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineMachinePinCommitPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.commitId);
    }

    public static TimelineMachinePinCommitPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelineMachinePinCommitPacket(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(TimelineMachinePinCommitPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.setPinnedCommit(packet.commitId);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
