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
 * Client -> server: sets which kinds of entity a timeline machine saves and loads when it handles
 * entities at all, dropped items and everything else.
 */
public class ChronosphereSetEntityKindsPacket implements CustomPacketPayload {
    public static final Type<ChronosphereSetEntityKindsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_set_entity_kinds"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereSetEntityKindsPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereSetEntityKindsPacket::encode, ChronosphereSetEntityKindsPacket::decode);

    private final BlockPos machinePos;
    private final boolean items;
    private final boolean mobs;

    public ChronosphereSetEntityKindsPacket(BlockPos machinePos, boolean items, boolean mobs) {
        this.machinePos = machinePos;
        this.items = items;
        this.mobs = mobs;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereSetEntityKindsPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeBoolean(packet.items);
        buf.writeBoolean(packet.mobs);
    }

    public static ChronosphereSetEntityKindsPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereSetEntityKindsPacket(buf.readBlockPos(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(ChronosphereSetEntityKindsPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.setEntityKinds(packet.items, packet.mobs);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
