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

/** Client -> server: wipe a timeline machine's tracked chunk(s) back to a blank history, leaving
 * the live world untouched (see the GUI's settings tab). Shared by the Chronosphere and Chronovault
 * screens — both menus implement TimelineViewMenu, and both block entities extend
 * AbstractTimelineMachineBlockEntity, which is where deleteAllHistory() actually lives. */
public class TimelineMachineDeleteHistoryPacket implements CustomPacketPayload {
    public static final Type<TimelineMachineDeleteHistoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_machine_delete_history"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineMachineDeleteHistoryPacket> STREAM_CODEC =
            StreamCodec.of(TimelineMachineDeleteHistoryPacket::encode, TimelineMachineDeleteHistoryPacket::decode);

    private final BlockPos machinePos;

    public TimelineMachineDeleteHistoryPacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineMachineDeleteHistoryPacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static TimelineMachineDeleteHistoryPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelineMachineDeleteHistoryPacket(buf.readBlockPos());
    }

    public static void handle(TimelineMachineDeleteHistoryPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(menu.getTimelineProvider() instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.deleteAllHistory();

            // The Chronosphere GUI's claim map/tracked-count footer reads from ChronosphereClientState
            // rather than the synced block entity (see ChronosphereScreen's renderMapOverlay), so it
            // needs this extra push; Chronovault has no such side state to refresh.
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
