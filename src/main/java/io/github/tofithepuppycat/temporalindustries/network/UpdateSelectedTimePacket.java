package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimeMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.TimeMachineMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: persists the block entity's selectedGameTime without applying it to the world. */
public class UpdateSelectedTimePacket implements CustomPacketPayload {
    public static final Type<UpdateSelectedTimePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "update_selected_time"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateSelectedTimePacket> STREAM_CODEC =
            StreamCodec.of(UpdateSelectedTimePacket::encode, UpdateSelectedTimePacket::decode);

    private final BlockPos machinePos;
    private final long targetGameTime;

    public UpdateSelectedTimePacket(BlockPos machinePos, long targetGameTime) {
        this.machinePos = machinePos;
        this.targetGameTime = targetGameTime;
    }

    public static void encode(RegistryFriendlyByteBuf buf, UpdateSelectedTimePacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.targetGameTime);
    }

    public static UpdateSelectedTimePacket decode(RegistryFriendlyByteBuf buf) {
        return new UpdateSelectedTimePacket(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(UpdateSelectedTimePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimeMachineMenu)) {
                return;
            }
            TimeMachineMenu menu = (TimeMachineMenu) sender.containerMenu;

            if (!menu.getBlockPos().equals(packet.machinePos)) {
                return;
            }

            BlockEntity blockEntity = sender.level().getBlockEntity(packet.machinePos);
            if (blockEntity instanceof TimeMachineBlockEntity) {
                TimeMachineBlockEntity timeMachineBlockEntity = (TimeMachineBlockEntity) blockEntity;
                timeMachineBlockEntity.setSelectedGameTime(packet.targetGameTime, false);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
