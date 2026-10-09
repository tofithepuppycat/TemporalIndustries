package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: forces an immediate, playerMarked commit of everything accumulated so far,
 * instead of waiting for the next automatic flush tick. Shared by the Chronosphere and Chronovault
 * screens, same pattern as {@link TimelineMachineDeleteHistoryPacket}. */
public class TimelineMachineCommitPacket implements CustomPacketPayload {
    public static final Type<TimelineMachineCommitPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_machine_commit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineMachineCommitPacket> STREAM_CODEC =
            StreamCodec.of(TimelineMachineCommitPacket::encode, TimelineMachineCommitPacket::decode);

    private final BlockPos machinePos;

    public TimelineMachineCommitPacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineMachineCommitPacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static TimelineMachineCommitPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelineMachineCommitPacket(buf.readBlockPos());
    }

    public static void handle(TimelineMachineCommitPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(menu.getTimelineProvider() instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.commitNow();

            if (machine instanceof ChronosphereBlockEntity chronosphere) {
                ChronosphereStateRequestPacket.sendStateSync(sender, chronosphere, packet.machinePos);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
