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
 * Client -> server: sets whether a timeline machine's jumps also set the time of day to the
 * target commit's.
 */
public class ChronosphereSetRollTimeOfDayPacket implements CustomPacketPayload {
    public static final Type<ChronosphereSetRollTimeOfDayPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_set_roll_time_of_day"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereSetRollTimeOfDayPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereSetRollTimeOfDayPacket::encode, ChronosphereSetRollTimeOfDayPacket::decode);

    private final BlockPos machinePos;
    private final boolean rollTimeOfDay;

    public ChronosphereSetRollTimeOfDayPacket(BlockPos machinePos, boolean rollTimeOfDay) {
        this.machinePos = machinePos;
        this.rollTimeOfDay = rollTimeOfDay;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereSetRollTimeOfDayPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeBoolean(packet.rollTimeOfDay);
    }

    public static ChronosphereSetRollTimeOfDayPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereSetRollTimeOfDayPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(ChronosphereSetRollTimeOfDayPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.setRollTimeOfDay(packet.rollTimeOfDay);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
