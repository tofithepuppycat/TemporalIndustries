package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@SuppressWarnings("null")
public class TimelinePreviewRequestPacket implements CustomPacketPayload {
    public static final Type<TimelinePreviewRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_preview_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelinePreviewRequestPacket> STREAM_CODEC =
            StreamCodec.of(TimelinePreviewRequestPacket::encode, TimelinePreviewRequestPacket::decode);

    private final BlockPos machinePos;
    private final long lastKnownHeadCommitId;
    /** The client's last-known preview fingerprint, covering everything the ghost preview depends
     * on (all previewed chunks plus glue state) that headCommitId alone wouldn't catch. */
    private final long lastKnownPreviewVersion;
    /** Which viewable chunk to show, or null for the shared/default view. */
    @Nullable
    private final ChunkPos viewChunk;

    public TimelinePreviewRequestPacket(BlockPos machinePos, long lastKnownHeadCommitId, long lastKnownPreviewVersion) {
        this(machinePos, lastKnownHeadCommitId, lastKnownPreviewVersion, null);
    }

    public TimelinePreviewRequestPacket(BlockPos machinePos, long lastKnownHeadCommitId, long lastKnownPreviewVersion,
                                        @Nullable ChunkPos viewChunk) {
        this.machinePos = machinePos;
        this.lastKnownHeadCommitId = lastKnownHeadCommitId;
        this.lastKnownPreviewVersion = lastKnownPreviewVersion;
        this.viewChunk = viewChunk;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelinePreviewRequestPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.lastKnownHeadCommitId);
        buf.writeLong(packet.lastKnownPreviewVersion);
        buf.writeBoolean(packet.viewChunk != null);
        if (packet.viewChunk != null) {
            buf.writeInt(packet.viewChunk.x);
            buf.writeInt(packet.viewChunk.z);
        }
    }

    public static TimelinePreviewRequestPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos machinePos = buf.readBlockPos();
        long lastKnownHeadCommitId = buf.readLong();
        long lastKnownPreviewVersion = buf.readLong();
        ChunkPos viewChunk = buf.readBoolean() ? new ChunkPos(buf.readInt(), buf.readInt()) : null;
        return new TimelinePreviewRequestPacket(machinePos, lastKnownHeadCommitId, lastKnownPreviewVersion, viewChunk);
    }

    /** Max distance (blocks) the sender may be from machinePos. Generous rather than a tight
     * interaction range, since the ghost preview keeps refreshing while the player walks away
     * from the GUI. */
    private static final double MAX_RANGE_BLOCKS = 256.0D;

    public static void handle(TimelinePreviewRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (sender.blockPosition().distSqr(packet.machinePos) > MAX_RANGE_BLOCKS * MAX_RANGE_BLOCKS) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof TimelineViewProvider machine)) return;

            ChunkPos viewChunk = packet.viewChunk;

            // headCommitId is a cheap "has this chunk's history changed" fingerprint, avoiding a full
            // commit-list re-encode on every poll. It only covers the displayed chunk though, so
            // previewVersion also catches changes to other previewed chunks and glue state.
            long headCommitId = machine.getChunkHeadId(viewChunk);
            long previewVersion = computePreviewVersion(sender, machine);
            if (headCommitId == packet.lastKnownHeadCommitId && previewVersion == packet.lastKnownPreviewVersion) {
                return;
            }

            TimelinePreviewSyncPacket syncPacket = new TimelinePreviewSyncPacket(
                    packet.machinePos,
                    machine.getPlacedGameTime(),
                    machine.getSelectedGameTime(),
                    sender.level().getGameTime(),
                    machine.getChunkCommits(viewChunk),
                    machine.getChunkLocalParents(viewChunk),
                    headCommitId,
                    machine.getSelectedCommitId(viewChunk),
                    machine.getChunkJumpCosts(viewChunk),
                    machine.getPreviewChunkSnapshots(),
                    TemporalWorldData.get(sender.getServer()).getGluedRegions(sender.level().dimension().location()),
                    previewVersion);

            PacketDistributor.sendToPlayer(sender, syncPacket);
        });
    }

    /** Combines every previewed chunk's head id with the world's glue-state version, so changes
     * invisible to headCommitId alone still invalidate the client's cache. */
    private static long computePreviewVersion(ServerPlayer sender, TimelineViewProvider machine) {
        List<ChunkPos> previewChunks = machine.getViewableChunks();
        long combined = 0L;
        if (previewChunks.isEmpty()) {
            combined = machine.getChunkHeadId(null);
        } else {
            for (ChunkPos chunk : previewChunks) {
                combined = combined * 1_000_003L + machine.getChunkHeadId(chunk);
            }
        }
        combined = combined * 1_000_003L + TemporalWorldData.get(sender.getServer()).getGlueVersion();
        return combined;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
