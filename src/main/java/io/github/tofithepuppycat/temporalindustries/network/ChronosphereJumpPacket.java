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

/** Client -> server: jump a Chronosphere's whole claimed chunk set to targetGameTime. */
public class ChronosphereJumpPacket implements CustomPacketPayload {
    public static final Type<ChronosphereJumpPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_jump"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereJumpPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereJumpPacket::encode, ChronosphereJumpPacket::decode);

    private final BlockPos machinePos;
    private final long targetGameTime;

    public ChronosphereJumpPacket(BlockPos machinePos, long targetGameTime) {
        this.machinePos = machinePos;
        this.targetGameTime = targetGameTime;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereJumpPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.targetGameTime);
    }

    public static ChronosphereJumpPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereJumpPacket(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(ChronosphereJumpPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof ChronosphereMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            if (sender.distanceToSqr(packet.machinePos.getX() + 0.5D, packet.machinePos.getY() + 0.5D, packet.machinePos.getZ() + 0.5D) > 64.0D) {
                return;
            }

            BlockEntity blockEntity = sender.level().getBlockEntity(packet.machinePos);
            if (blockEntity instanceof ChronosphereBlockEntity chronosphere) {
                chronosphere.jump(packet.targetGameTime);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
