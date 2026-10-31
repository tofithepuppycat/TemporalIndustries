package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.RedstoneMode;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: sets how a timeline machine reacts to redstone. */
public class TimelineMachineSetRedstoneModePacket implements CustomPacketPayload {
    public static final Type<TimelineMachineSetRedstoneModePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_machine_set_redstone_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineMachineSetRedstoneModePacket> STREAM_CODEC =
            StreamCodec.of(TimelineMachineSetRedstoneModePacket::encode, TimelineMachineSetRedstoneModePacket::decode);

    private final BlockPos machinePos;
    private final RedstoneMode mode;

    public TimelineMachineSetRedstoneModePacket(BlockPos machinePos, RedstoneMode mode) {
        this.machinePos = machinePos;
        this.mode = mode;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineMachineSetRedstoneModePacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeVarInt(packet.mode.ordinal());
    }

    public static TimelineMachineSetRedstoneModePacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelineMachineSetRedstoneModePacket(buf.readBlockPos(), RedstoneMode.byOrdinal(buf.readVarInt()));
    }

    public static void handle(TimelineMachineSetRedstoneModePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.setRedstoneMode(packet.mode);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
