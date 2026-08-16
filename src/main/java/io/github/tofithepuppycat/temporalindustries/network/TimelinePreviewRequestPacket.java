package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
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
    /** The client's last-known previewVersion fingerprint (see {@link TimelinePreviewSyncPacket}) —
     * covers everything the ghost preview (not just the displayed graph) depends on: every chunk in
     * getPreviewChunkSnapshots(), plus glue state. lastKnownHeadCommitId alone can't catch either
     * (see #handle below). */
    private final long lastKnownPreviewVersion;
    /** Which of TimelineViewProvider#getViewableChunks() to show, or null for the shared/default
     * view (a Time Machine's own chunk; a Chronosphere's merged "All" view). */
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

    public static void handle(TimelinePreviewRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            TimelineViewProvider machine = menu.getTimelineProvider();
            ChunkPos viewChunk = packet.viewChunk;

            // The chunk's head id changes whenever any commit relevant to it is created (see
            // TemporalTimeline#indexChunkTouch), so it's a cheap fingerprint for "has this
            // machine's history changed since the client last saw it" — skipping the full commit
            // list re-encode/re-decode (which only grows over a session) on every periodic poll
            // when nothing actually happened avoids paying that cost once a second for nothing.
            // On its own this only covers the displayed chunk, though: the ghost preview
            // (previewChunkSnapshots) can cover other claimed chunks too — e.g. an auto-tracked
            // Chronosphere chunk that isn't the one currently shown, or isn't shared across every
            // claimed chunk yet — and it also depends on glue state, which never creates a commit
            // at all. previewVersion below catches both; only skip the reply when neither changed.
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

    /** Combines every chunk the ghost preview covers' head id (cheap map lookups — see
     * TemporalTimeline#getChunkHeadId — not the full commit-list copies getChunkCommits() does)
     * with the world's glue-state version, so a change invisible to headCommitId alone (an
     * unshared per-chunk commit, or a glue/unglue with no commit at all) still invalidates the
     * client's cache. */
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
