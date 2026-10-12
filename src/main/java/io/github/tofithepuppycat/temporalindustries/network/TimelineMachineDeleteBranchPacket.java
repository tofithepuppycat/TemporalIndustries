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
 * Client -> server: deletes a named branch (and the history unique to it) from a timeline machine's
 * repository, provided it isn't the branch currently checked out.
 */
public class TimelineMachineDeleteBranchPacket implements CustomPacketPayload {
    public static final Type<TimelineMachineDeleteBranchPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_machine_delete_branch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelineMachineDeleteBranchPacket> STREAM_CODEC =
            StreamCodec.of(TimelineMachineDeleteBranchPacket::encode, TimelineMachineDeleteBranchPacket::decode);

    private final BlockPos machinePos;
    private final String branchName;

    public TimelineMachineDeleteBranchPacket(BlockPos machinePos, String branchName) {
        this.machinePos = machinePos;
        this.branchName = branchName;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelineMachineDeleteBranchPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeUtf(packet.branchName, 64);
    }

    public static TimelineMachineDeleteBranchPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelineMachineDeleteBranchPacket(buf.readBlockPos(), buf.readUtf(64));
    }

    public static void handle(TimelineMachineDeleteBranchPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(menu.getTimelineProvider() instanceof AbstractTimelineMachineBlockEntity machine)) return;

            machine.deleteBranch(packet.branchName);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
