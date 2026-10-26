package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client -> server: poll for a machine's repository graph, plus the resolved ghost-preview diff for
 * whichever commit the player currently has selected.
 */
@SuppressWarnings("null")
public class TimelinePreviewRequestPacket implements CustomPacketPayload {
    public static final Type<TimelinePreviewRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_preview_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelinePreviewRequestPacket> STREAM_CODEC =
            StreamCodec.of(TimelinePreviewRequestPacket::encode, TimelinePreviewRequestPacket::decode);

    /** Hard ceiling on synced preview entries. */
    private static final int MAX_PREVIEW_ENTRIES = 60_000;

    private final BlockPos machinePos;
    private final long lastKnownHeadCommitId;
    /**
     * The client's last-known preview fingerprint, covering everything the ghost preview depends on
     * (selection and glue state) that headCommitId alone wouldn't catch.
     */
    private final long lastKnownPreviewVersion;
    /** The commit the player has selected, or -1 for none. */
    private final long selectedCommitId;

    public TimelinePreviewRequestPacket(BlockPos machinePos, long lastKnownHeadCommitId, long lastKnownPreviewVersion) {
        this(machinePos, lastKnownHeadCommitId, lastKnownPreviewVersion, -1L);
    }

    public TimelinePreviewRequestPacket(BlockPos machinePos, long lastKnownHeadCommitId, long lastKnownPreviewVersion,
                                        long selectedCommitId) {
        this.machinePos = machinePos;
        this.lastKnownHeadCommitId = lastKnownHeadCommitId;
        this.lastKnownPreviewVersion = lastKnownPreviewVersion;
        this.selectedCommitId = selectedCommitId;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelinePreviewRequestPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.lastKnownHeadCommitId);
        buf.writeLong(packet.lastKnownPreviewVersion);
        buf.writeLong(packet.selectedCommitId);
    }

    public static TimelinePreviewRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new TimelinePreviewRequestPacket(buf.readBlockPos(), buf.readLong(), buf.readLong(), buf.readLong());
    }

    /** Max distance (blocks) the sender may be from machinePos. */
    private static final double MAX_RANGE_BLOCKS = 256.0D;

    public static void handle(TimelinePreviewRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            // A Chrono Remote Control reads its linked machine from any distance, as long as it is loaded.
            GlobalPos remoteLink = ChronoRemoteControlItem.findHeldLink(sender);
            boolean viaRemote = remoteLink != null && remoteLink.dimension().equals(sender.level().dimension())
                    && remoteLink.pos().equals(packet.machinePos);
            if (viaRemote ? !sender.level().isLoaded(packet.machinePos)
                    : sender.blockPosition().distSqr(packet.machinePos) > MAX_RANGE_BLOCKS * MAX_RANGE_BLOCKS) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof TimelineViewProvider machine)) return;

            // headCommitId is a cheap "has history moved" fingerprint, avoiding a full re-encode on
            // every poll. previewVersion additionally catches a changed selection or glue state,
            // which the head id alone wouldn't.
            long headCommitId = machine.getHeadCommitId();
            long previewVersion = computePreviewVersion(sender, machine, packet.selectedCommitId);
            if (headCommitId == packet.lastKnownHeadCommitId && previewVersion == packet.lastKnownPreviewVersion) {
                return;
            }

            List<CommitNode> nodes = new ArrayList<>();
            for (RepoCommit commit : machine.getRepositoryCommits()) nodes.add(CommitNode.of(commit));

            PacketDistributor.sendToPlayer(sender, new TimelinePreviewSyncPacket(
                    packet.machinePos,
                    machine.getPlacedGameTime(),
                    machine.getSelectedGameTime(),
                    sender.level().getGameTime(),
                    nodes,
                    machine.getBranchTips(),
                    machine.getCurrentNamedBranch(),
                    headCommitId,
                    machine.getJumpCosts(packet.selectedCommitId),
                    resolvePreview(machine, packet.selectedCommitId, sender),
                    machine.getClaimedChunks(),
                    previewVersion));
        });
    }

    /**
     * Flattens the repository's resolved target states for selectedCommitId into the {@code
     * BlockPos -> target state} map the client renders, dropping glued positions (a jump skips
     * those entirely) and positions already matching the world.
     */
    private static Map<BlockPos, BlockState> resolvePreview(TimelineViewProvider machine, long selectedCommitId,
                                                              ServerPlayer viewer) {
        if (selectedCommitId < 0) return Map.of();
        net.minecraft.world.level.Level level = viewer.level();

        // Resolving a chunk copies all of its block states, so on a huge claim resolve nearest-
        // first and stop the moment the entry cap is hit, rather than resolving every affected
        // chunk up front.
        ChunkPos center = viewer.chunkPosition();
        List<ChunkPos> chunks = new ArrayList<>(machine.previewAffectedChunks(selectedCommitId));
        chunks.sort(java.util.Comparator.comparingLong(c -> {
            long dx = c.x - center.x, dz = c.z - center.z;
            return dx * dx + dz * dz;
        }));

        Map<BlockPos, BlockState> out = new HashMap<>();
        for (ChunkPos chunk : chunks) {
            if (out.size() >= MAX_PREVIEW_ENTRIES) break;
            if (!level.hasChunk(chunk.x, chunk.z)) continue;
            for (Map.Entry<BlockPos, BlockState> entry : machine.previewChunkTargetStates(selectedCommitId, chunk).entrySet()) {
                if (machine.isGluedPos(entry.getKey())) continue;
                if (level.getBlockState(entry.getKey()).equals(entry.getValue())) continue;
                if (out.size() >= MAX_PREVIEW_ENTRIES) return out;
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /**
     * Combines the repository head with the player's selection and the world's glue-state version,
     * so changes invisible to headCommitId alone still invalidate the client's cache.
     */
    private static long computePreviewVersion(ServerPlayer sender, TimelineViewProvider machine, long selectedCommitId) {
        long combined = machine.getHeadCommitId();
        combined = combined * 1_000_003L + selectedCommitId;
        for (ChunkPos chunk : machine.getClaimedChunks()) {
            combined = combined * 1_000_003L + chunk.toLong();
        }
        combined = combined * 1_000_003L + TemporalWorldData.get(sender.getServer()).getGlueVersion();
        return combined;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
