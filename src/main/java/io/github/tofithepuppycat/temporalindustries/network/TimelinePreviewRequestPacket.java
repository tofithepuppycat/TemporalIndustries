package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
import io.github.tofithepuppycat.temporalindustries.timeline.JumpCostBreakdown;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    /** Hard ceiling on synced entity ghosts, nearest to the viewer first. */
    private static final int MAX_PREVIEW_ENTITIES = 64;

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

    private static final int MIN_REQUEST_INTERVAL_TICKS = 5;

    public static void handle(TimelinePreviewRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!RequestThrottle.tryAcquire(sender, RequestThrottle.TIMELINE_PREVIEW, MIN_REQUEST_INTERVAL_TICKS)) return;

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
            // Measured in the background, so it may not be ready yet; the version flips once it is.
            Map<Long, JumpCostBreakdown> jumpCosts = machine.getJumpCosts(packet.selectedCommitId);
            // Edits that raise no block event only show up by comparing the world, so the pending
            // changes are resolved on every poll and their contents are part of the version.
            boolean pending = packet.selectedCommitId == TimelineViewProvider.PENDING_CHANGES;
            Map<BlockPos, BlockState> pendingBlocks = pending ? resolvePreview(machine, packet.selectedCommitId, sender) : null;
            long previewVersion = computePreviewVersion(sender, machine, packet.selectedCommitId, !jumpCosts.isEmpty(),
                    pendingBlocks == null ? 0L : pendingBlocks.hashCode());
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
                    jumpCosts,
                    pending ? pendingBlocks : resolvePreview(machine, packet.selectedCommitId, sender),
                    machine.getClaimedChunks(),
                    resolveEntityPreview(machine, packet.selectedCommitId, sender),
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
        if (selectedCommitId == TimelineViewProvider.PENDING_CHANGES) {
            Map<BlockPos, BlockState> out = new HashMap<>();
            for (Map.Entry<BlockPos, BlockState> entry : machine.previewPendingStates(viewer.chunkPosition(), MAX_PREVIEW_ENTRIES).entrySet()) {
                if (machine.isGluedPos(entry.getKey())) continue;
                if (viewer.level().getBlockState(entry.getKey()).equals(entry.getValue())) continue;
                out.put(entry.getKey(), entry.getValue());
            }
            return out;
        }
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
     * The recorded state of each entity a jump to selectedCommitId would restore, nearest the viewer
     * first. Entities already standing where they were recorded are left out, as they would not
     * visibly change.
     */
    private static List<CompoundTag> resolveEntityPreview(TimelineViewProvider machine, long selectedCommitId,
                                                          ServerPlayer viewer) {
        if (selectedCommitId < 0 || !machine.isSaveEntities()) return List.of();
        ServerLevel level = viewer.serverLevel();

        record Candidate(CompoundTag tag, double distanceSqr) {}
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<UUID, CompoundTag> entry : machine.previewEntityStates(selectedCommitId).entrySet()) {
            CompoundTag tag = entry.getValue();
            ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
            if (pos.size() < 3) continue;
            if (tag.contains("Health") && tag.getFloat("Health") <= 0.0F) continue;

            double x = pos.getDouble(0), y = pos.getDouble(1), z = pos.getDouble(2);
            Entity live = level.getEntity(entry.getKey());
            if (live != null && !live.isRemoved() && live.distanceToSqr(x, y, z) < 0.25D) continue;
            candidates.add(new Candidate(tag, viewer.distanceToSqr(x, y, z)));
        }

        candidates.sort(java.util.Comparator.comparingDouble(Candidate::distanceSqr));
        List<CompoundTag> out = new ArrayList<>(Math.min(candidates.size(), MAX_PREVIEW_ENTITIES));
        for (Candidate candidate : candidates) {
            if (out.size() >= MAX_PREVIEW_ENTITIES) break;
            out.add(candidate.tag());
        }
        return out;
    }

    /**
     * Combines the repository head with the player's selection and the world's glue-state version,
     * so changes invisible to headCommitId alone still invalidate the client's cache.
     */
    private static long computePreviewVersion(ServerPlayer sender, TimelineViewProvider machine, long selectedCommitId,
                                              boolean costsReady, long pendingHash) {
        long combined = machine.getHeadCommitId();
        combined = combined * 1_000_003L + selectedCommitId;
        combined = combined * 1_000_003L + (costsReady ? 1L : 0L);
        combined = combined * 1_000_003L + pendingHash;
        combined = combined * 1_000_003L + (machine.isSaveEntities() ? 1L : 0L);
        combined = combined * 1_000_003L + (machine.isSaveItemEntities() ? 1L : 0L);
        combined = combined * 1_000_003L + (machine.isSaveMobEntities() ? 1L : 0L);
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
