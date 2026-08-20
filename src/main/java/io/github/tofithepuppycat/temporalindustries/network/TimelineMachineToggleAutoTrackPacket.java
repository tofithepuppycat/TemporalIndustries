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

/** Client -> server: flip a timeline machine's auto-tracking flag (see the GUI's auto-track tab).
 * Shared by the Chronosphere and Chronovault screens — see TimelineMachineDeleteHistoryPacket for
 * why this can operate through the common TimelineViewMenu/AbstractTimelineMachineBlockEntity types. */
public class TimelineMachineToggleAutoTrackPacket implements CustomPacketPayload {
    public static final Type<TimelineMachineToggleAutoTrackPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_machine_toggle_auto_track"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineMachineToggleAutoTrackPacket> STREAM_CODEC =
            StreamCodec.of(TimelineMachineToggleAutoTrackPacket::encode, TimelineMachineToggleAutoTrackPacket::decode);

    private final BlockPos machinePos;
    private final boolean enabled;

    public TimelineMachineToggleAutoTrackPacket(BlockPos machinePos, boolean enabled) {
        this.machinePos = machinePos;
        this.enabled = enabled;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineMachineToggleAutoTrackPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeBoolean(packet.enabled);
    }

    public static TimelineMachineToggleAutoTrackPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelineMachineToggleAutoTrackPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(TimelineMachineToggleAutoTrackPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(menu.getTimelineProvider() instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.setAutoTrackingEnabled(packet.enabled);

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
