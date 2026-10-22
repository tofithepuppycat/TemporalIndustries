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
 * Client -> server: sets whether a timeline machine's jumps also restore the entities recorded in the
 * target commit.
 */
public class ChronosphereSetSaveEntitiesPacket implements CustomPacketPayload {
    public static final Type<ChronosphereSetSaveEntitiesPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_set_save_entities"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereSetSaveEntitiesPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereSetSaveEntitiesPacket::encode, ChronosphereSetSaveEntitiesPacket::decode);

    private final BlockPos machinePos;
    private final boolean saveEntities;

    public ChronosphereSetSaveEntitiesPacket(BlockPos machinePos, boolean saveEntities) {
        this.machinePos = machinePos;
        this.saveEntities = saveEntities;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereSetSaveEntitiesPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeBoolean(packet.saveEntities);
    }

    public static ChronosphereSetSaveEntitiesPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereSetSaveEntitiesPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(ChronosphereSetSaveEntitiesPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.setSaveEntities(packet.saveEntities);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
