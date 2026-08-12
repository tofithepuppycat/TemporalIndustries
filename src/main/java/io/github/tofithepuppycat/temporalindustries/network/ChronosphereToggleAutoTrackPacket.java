package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: flip a Chronosphere's auto-tracking flag (see the GUI's auto-track tab).
 * Triggers an immediate ChronosphereStateSyncPacket reply, same as ChronosphereToggleChunkPacket. */
public class ChronosphereToggleAutoTrackPacket implements CustomPacketPayload {
    public static final Type<ChronosphereToggleAutoTrackPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_toggle_auto_track"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereToggleAutoTrackPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereToggleAutoTrackPacket::encode, ChronosphereToggleAutoTrackPacket::decode);

    private final BlockPos machinePos;
    private final boolean enabled;

    public ChronosphereToggleAutoTrackPacket(BlockPos machinePos, boolean enabled) {
        this.machinePos = machinePos;
        this.enabled = enabled;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereToggleAutoTrackPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeBoolean(packet.enabled);
    }

    public static ChronosphereToggleAutoTrackPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereToggleAutoTrackPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(ChronosphereToggleAutoTrackPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof ChronosphereMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof ChronosphereBlockEntity machine)) return;

            machine.setAutoTrackingEnabled(packet.enabled);
            ChronosphereStateRequestPacket.sendStateSync(sender, machine, packet.machinePos);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
