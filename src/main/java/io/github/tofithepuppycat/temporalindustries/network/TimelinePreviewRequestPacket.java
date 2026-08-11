package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@SuppressWarnings("null")
public class TimelinePreviewRequestPacket implements CustomPacketPayload {
    public static final Type<TimelinePreviewRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_preview_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelinePreviewRequestPacket> STREAM_CODEC =
            StreamCodec.of(TimelinePreviewRequestPacket::encode, TimelinePreviewRequestPacket::decode);

    private final BlockPos machinePos;
    private final long lastKnownHeadCommitId;

    public TimelinePreviewRequestPacket(BlockPos machinePos, long lastKnownHeadCommitId) {
        this.machinePos = machinePos;
        this.lastKnownHeadCommitId = lastKnownHeadCommitId;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelinePreviewRequestPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.lastKnownHeadCommitId);
    }

    public static TimelinePreviewRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelinePreviewRequestPacket(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(TimelinePreviewRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            TimelineViewProvider machine = menu.getTimelineProvider();

            // The chunk's head id changes whenever any commit relevant to it is created (see
            // TemporalTimeline#indexChunkTouch), so it's a cheap fingerprint for "has this
            // machine's history changed since the client last saw it" — skipping the full commit
            // list re-encode/re-decode (which only grows over a session) on every periodic poll
            // when nothing actually happened avoids paying that cost once a second for nothing.
            long headCommitId = machine.getChunkHeadId();
            if (headCommitId == packet.lastKnownHeadCommitId) return;

            TimelinePreviewSyncPacket syncPacket = new TimelinePreviewSyncPacket(
                    packet.machinePos,
                    machine.getPlacedGameTime(),
                    machine.getSelectedGameTime(),
                    sender.level().getGameTime(),
                    machine.getChunkCommits(),
                    machine.getChunkLocalParents(),
                    headCommitId,
                    machine.getSelectedCommitId(),
                    machine.getChunkJumpCosts());

            PacketDistributor.sendToPlayer(sender, syncPacket);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
