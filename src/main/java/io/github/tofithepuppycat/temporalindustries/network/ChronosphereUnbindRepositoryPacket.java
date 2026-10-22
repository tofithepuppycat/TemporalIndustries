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

/**
 * Client -> server: detaches a Chronosphere or Chronovault from its repository so a different one can be chosen.
 */
public class ChronosphereUnbindRepositoryPacket implements CustomPacketPayload {
    public static final Type<ChronosphereUnbindRepositoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_unbind_repository"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereUnbindRepositoryPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereUnbindRepositoryPacket::encode, ChronosphereUnbindRepositoryPacket::decode);

    private final BlockPos machinePos;

    public ChronosphereUnbindRepositoryPacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereUnbindRepositoryPacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static ChronosphereUnbindRepositoryPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereUnbindRepositoryPacket(buf.readBlockPos());
    }

    public static void handle(ChronosphereUnbindRepositoryPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.unbindRepository();
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
