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

/** Client -> server: persists the Chronosphere's selectedGameTime without applying it to the world. */
public class UpdateChronosphereTimePacket implements CustomPacketPayload {
    public static final Type<UpdateChronosphereTimePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "update_chronosphere_time"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateChronosphereTimePacket> STREAM_CODEC =
            StreamCodec.of(UpdateChronosphereTimePacket::encode, UpdateChronosphereTimePacket::decode);

    private final BlockPos machinePos;
    private final long targetGameTime;

    public UpdateChronosphereTimePacket(BlockPos machinePos, long targetGameTime) {
        this.machinePos = machinePos;
        this.targetGameTime = targetGameTime;
    }

    public static void encode(RegistryFriendlyByteBuf buf, UpdateChronosphereTimePacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.targetGameTime);
    }

    public static UpdateChronosphereTimePacket decode(RegistryFriendlyByteBuf buf) {
        return new UpdateChronosphereTimePacket(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(UpdateChronosphereTimePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof ChronosphereMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity blockEntity = sender.level().getBlockEntity(packet.machinePos);
            if (blockEntity instanceof ChronosphereBlockEntity chronosphere) {
                chronosphere.setSelectedGameTime(packet.targetGameTime, false);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
