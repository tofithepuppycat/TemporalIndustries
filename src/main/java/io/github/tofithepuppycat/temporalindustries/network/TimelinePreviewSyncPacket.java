package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
import io.github.tofithepuppycat.temporalindustries.timeline.JumpCostBreakdown;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server -> client: response to {@link TimelinePreviewRequestPacket}, carrying a machine's
 * repository graph (as lightweight {@link CommitNode}s), its branch refs, and the already-resolved
 * ghost-preview block diff for the player's selected commit.
 */
public class TimelinePreviewSyncPacket implements CustomPacketPayload {
    public static final Type<TimelinePreviewSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_preview_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelinePreviewSyncPacket> STREAM_CODEC =
            StreamCodec.of(TimelinePreviewSyncPacket::encode, TimelinePreviewSyncPacket::decode);

    /** Added to a block's Y before packing it, so heights below zero stay non-negative. */
    private static final int HEIGHT_BIAS = 2048;

    private final BlockPos machinePos;
    private final long placedGameTime;
    private final long selectedGameTime;
    private final long currentGameTime;
    private final List<CommitNode> commits;
    private final Map<String, Long> branchTips;
    private final String currentBranch;
    private final long headCommitId;
    /** commitId -> energy cost of jumping there from the current head. */
    private final Map<Long, JumpCostBreakdown> jumpCosts;
    /**
     * Resolved target state of every block the selected jump would change, glue already filtered.
     */
    private final Map<BlockPos, BlockState> previewTargetStates;
    /** Every chunk the machine has claimed, for the in-world boundary outline. */
    private final List<ChunkPos> previewChunks;
    /** Recorded state of each entity the selected jump would restore, for the in-world ghosts. */
    private final List<CompoundTag> previewEntities;
    /** Fingerprint matching {@link TimelinePreviewRequestPacket}'s lastKnownPreviewVersion. */
    private final long previewVersion;

    public TimelinePreviewSyncPacket(BlockPos machinePos, long placedGameTime, long selectedGameTime,
                                     long currentGameTime, List<CommitNode> commits,
                                     Map<String, Long> branchTips, String currentBranch, long headCommitId,
                                     Map<Long, JumpCostBreakdown> jumpCosts, Map<BlockPos, BlockState> previewTargetStates,
                                     List<ChunkPos> previewChunks, List<CompoundTag> previewEntities,
                                     long previewVersion) {
        this.machinePos = machinePos;
        this.placedGameTime = placedGameTime;
        this.selectedGameTime = selectedGameTime;
        this.currentGameTime = currentGameTime;
        this.commits = commits;
        this.branchTips = branchTips;
        this.currentBranch = currentBranch;
        this.headCommitId = headCommitId;
        this.jumpCosts = jumpCosts;
        this.previewTargetStates = previewTargetStates;
        this.previewChunks = previewChunks;
        this.previewEntities = previewEntities;
        this.previewVersion = previewVersion;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelinePreviewSyncPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.placedGameTime);
        buf.writeLong(packet.selectedGameTime);
        buf.writeLong(packet.currentGameTime);

        // The preview diff can run to tens of thousands of positions, so DEFLATE the body rather
        // than writing it raw to keep large payloads off the per-connection rate limit.
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        body.writeVarInt(packet.commits.size());
        for (CommitNode node : packet.commits) CommitNode.encode(node, body);
        body.writeVarInt(packet.branchTips.size());
        for (Map.Entry<String, Long> entry : packet.branchTips.entrySet()) {
            body.writeUtf(entry.getKey(), 64);
            body.writeLong(entry.getValue());
        }
        body.writeVarInt(packet.jumpCosts.size());
        for (Map.Entry<Long, JumpCostBreakdown> entry : packet.jumpCosts.entrySet()) {
            body.writeLong(entry.getKey());
            JumpCostBreakdown.encode(entry.getValue(), body);
        }
        // Grouped by chunk so each position needs only its chunk-local offset, not a full BlockPos.
        Map<Long, List<Map.Entry<BlockPos, BlockState>>> byChunk = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : packet.previewTargetStates.entrySet()) {
            byChunk.computeIfAbsent(ChunkPos.asLong(entry.getKey()), k -> new ArrayList<>()).add(entry);
        }
        body.writeVarInt(byChunk.size());
        for (Map.Entry<Long, List<Map.Entry<BlockPos, BlockState>>> group : byChunk.entrySet()) {
            body.writeLong(group.getKey());
            body.writeVarInt(group.getValue().size());
            for (Map.Entry<BlockPos, BlockState> entry : group.getValue()) {
                BlockPos pos = entry.getKey();
                body.writeVarInt((pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() + HEIGHT_BIAS) << 8));
                body.writeVarInt(Block.getId(entry.getValue()));
            }
        }
        body.writeVarInt(packet.previewChunks.size());
        for (ChunkPos chunk : packet.previewChunks) body.writeLong(chunk.toLong());
        body.writeVarInt(packet.previewEntities.size());
        for (CompoundTag entity : packet.previewEntities) body.writeNbt(entity);

        byte[] rawBody = new byte[body.readableBytes()];
        body.readBytes(rawBody);
        body.release();

        buf.writeVarInt(rawBody.length);
        buf.writeByteArray(CompressionUtil.compress(rawBody));

        buf.writeUtf(packet.currentBranch, 64);
        buf.writeLong(packet.headCommitId);
        buf.writeLong(packet.previewVersion);
    }

    public static TimelinePreviewSyncPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos machinePos = buf.readBlockPos();
        long placedGameTime = buf.readLong();
        long selectedGameTime = buf.readLong();
        long currentGameTime = buf.readLong();

        int rawBodyLength = buf.readVarInt();
        byte[] compressed = buf.readByteArray();
        byte[] rawBody = CompressionUtil.decompress(compressed);
        if (rawBody.length != rawBodyLength) {
            throw new IllegalStateException("Decompressed timeline preview payload size mismatch: expected "
                    + rawBodyLength + " but got " + rawBody.length);
        }
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.wrappedBuffer(rawBody));

        int commitCount = body.readVarInt();
        List<CommitNode> commits = new ArrayList<>(commitCount);
        for (int i = 0; i < commitCount; i++) commits.add(CommitNode.decode(body));

        int branchCount = body.readVarInt();
        Map<String, Long> branchTips = new HashMap<>();
        for (int i = 0; i < branchCount; i++) {
            String name = body.readUtf(64);
            branchTips.put(name, body.readLong());
        }

        int jumpCostCount = body.readVarInt();
        Map<Long, JumpCostBreakdown> jumpCosts = new HashMap<>();
        for (int i = 0; i < jumpCostCount; i++) {
            long commitId = body.readLong();
            jumpCosts.put(commitId, JumpCostBreakdown.decode(body));
        }

        int previewChunkGroups = body.readVarInt();
        Map<BlockPos, BlockState> previewTargetStates = new HashMap<>();
        for (int i = 0; i < previewChunkGroups; i++) {
            ChunkPos chunk = new ChunkPos(body.readLong());
            int count = body.readVarInt();
            for (int j = 0; j < count; j++) {
                int packed = body.readVarInt();
                BlockPos pos = new BlockPos(chunk.getMinBlockX() + (packed & 15), (packed >> 8) - HEIGHT_BIAS,
                        chunk.getMinBlockZ() + ((packed >> 4) & 15));
                previewTargetStates.put(pos, Block.stateById(body.readVarInt()));
            }
        }

        int previewChunkCount = body.readVarInt();
        List<ChunkPos> previewChunks = new ArrayList<>(previewChunkCount);
        for (int i = 0; i < previewChunkCount; i++) previewChunks.add(new ChunkPos(body.readLong()));

        int previewEntityCount = body.readVarInt();
        List<CompoundTag> previewEntities = new ArrayList<>(previewEntityCount);
        for (int i = 0; i < previewEntityCount; i++) {
            CompoundTag entity = body.readNbt();
            if (entity != null) previewEntities.add(entity);
        }
        body.release();

        String currentBranch = buf.readUtf(64);
        long headCommitId = buf.readLong();
        long previewVersion = buf.readLong();
        return new TimelinePreviewSyncPacket(machinePos, placedGameTime, selectedGameTime, currentGameTime,
                commits, branchTips, currentBranch, headCommitId, jumpCosts, previewTargetStates,
                previewChunks, previewEntities, previewVersion);
    }

    public static void handle(TimelinePreviewSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> TimelineProjectionManager.updateFromServer(
                packet.machinePos,
                packet.placedGameTime,
                packet.currentGameTime,
                packet.commits,
                packet.branchTips,
                packet.currentBranch,
                packet.headCommitId,
                packet.jumpCosts,
                packet.previewTargetStates,
                packet.previewChunks,
                packet.previewEntities,
                packet.previewVersion));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
