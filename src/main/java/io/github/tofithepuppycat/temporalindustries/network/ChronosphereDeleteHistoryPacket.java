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

/** Client -> server: wipe every chunk a Chronosphere has claimed back to a blank history, leaving
 * the live world untouched (see the GUI's settings tab). */
public class ChronosphereDeleteHistoryPacket implements CustomPacketPayload {
    public static final Type<ChronosphereDeleteHistoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_delete_history"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereDeleteHistoryPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereDeleteHistoryPacket::encode, ChronosphereDeleteHistoryPacket::decode);

    private final BlockPos machinePos;

    public ChronosphereDeleteHistoryPacket(BlockPos machinePos) {
        this.machinePos = machinePos;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereDeleteHistoryPacket packet) {
        buf.writeBlockPos(packet.machinePos);
    }

    public static ChronosphereDeleteHistoryPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereDeleteHistoryPacket(buf.readBlockPos());
    }

    public static void handle(ChronosphereDeleteHistoryPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof ChronosphereMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof ChronosphereBlockEntity machine)) return;

            machine.deleteAllHistory();
            ChronosphereStateRequestPacket.sendStateSync(sender, machine, packet.machinePos);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
