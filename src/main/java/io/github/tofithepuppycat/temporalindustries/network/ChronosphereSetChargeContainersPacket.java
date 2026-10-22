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
 * Client -> server: sets whether a timeline machine's jumps charge for the container contents they
 * add to the world.
 */
public class ChronosphereSetChargeContainersPacket implements CustomPacketPayload {
    public static final Type<ChronosphereSetChargeContainersPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_set_charge_containers"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereSetChargeContainersPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereSetChargeContainersPacket::encode, ChronosphereSetChargeContainersPacket::decode);

    private final BlockPos machinePos;
    private final boolean chargeContainers;

    public ChronosphereSetChargeContainersPacket(BlockPos machinePos, boolean chargeContainers) {
        this.machinePos = machinePos;
        this.chargeContainers = chargeContainers;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereSetChargeContainersPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeBoolean(packet.chargeContainers);
    }

    public static ChronosphereSetChargeContainersPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereSetChargeContainersPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(ChronosphereSetChargeContainersPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.setChargeContainers(packet.chargeContainers);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
