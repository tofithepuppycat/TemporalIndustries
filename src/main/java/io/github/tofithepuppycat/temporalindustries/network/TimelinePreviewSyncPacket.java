package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkTimelineSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Server -> client: response to {@link TimelinePreviewRequestPacket}, carrying a machine's chunk
 * commit graph for {@link TimelineProjectionManager}. */
public class TimelinePreviewSyncPacket implements CustomPacketPayload {
    public static final Type<TimelinePreviewSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "timeline_preview_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TimelinePreviewSyncPacket> STREAM_CODEC =
            StreamCodec.of(TimelinePreviewSyncPacket::encode, TimelinePreviewSyncPacket::decode);

    private final BlockPos machinePos;
    private final long placedGameTime;
    private final long selectedGameTime;
    private final long currentGameTime;
    private final List<TemporalCommit> commits;
    private final Map<Long, Long> localParents;
    private final long headCommitId;
    private final long selectedCommitId;
    /** commitId -> energy cost of jumping there from the chunk's current head. */
    private final Map<Long, Long> jumpCosts;
    /** One commit-graph snapshot per chunk the in-world ghost preview should cover — for a Time
     * Machine just its own chunk (duplicating the fields above), for a Chronosphere every chunk
     * it has claimed. See TimelineViewProvider#getPreviewChunkSnapshots(). */
    private final List<ChunkTimelineSnapshot> previewChunkSnapshots;
    /** Every glued region in the machine's dimension, as of this sync — a jump skips these
     * positions entirely (see TemporalTimeline's isGlued predicate), so the ghost preview needs
     * them too, independent of whatever GlueSelectionClientState has cached from a Temporal Glue
     * item that may not even be held right now. */
    private final List<BoundingBox> gluedRegions;
    /** See {@link TimelinePreviewRequestPacket}'s lastKnownPreviewVersion field doc. */
    private final long previewVersion;

    public TimelinePreviewSyncPacket(BlockPos machinePos, long placedGameTime, long selectedGameTime,
                                     long currentGameTime, List<TemporalCommit> commits,
                                     Map<Long, Long> localParents, long headCommitId, long selectedCommitId,
                                     Map<Long, Long> jumpCosts, List<ChunkTimelineSnapshot> previewChunkSnapshots,
                                     List<BoundingBox> gluedRegions, long previewVersion) {
        this.machinePos = machinePos;
        this.placedGameTime = placedGameTime;
        this.selectedGameTime = selectedGameTime;
        this.currentGameTime = currentGameTime;
        this.commits = commits;
        this.localParents = localParents;
        this.headCommitId = headCommitId;
        this.selectedCommitId = selectedCommitId;
        this.jumpCosts = jumpCosts;
        this.previewChunkSnapshots = previewChunkSnapshots;
        this.gluedRegions = gluedRegions;
        this.previewVersion = previewVersion;
    }

    public static void encode(RegistryFriendlyByteBuf buf, TimelinePreviewSyncPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.placedGameTime);
        buf.writeLong(packet.selectedGameTime);
        buf.writeLong(packet.currentGameTime);

        // Commits/parent map can carry an unbounded amount of block/entity delta NBT, so encode
        // them into a scratch buffer and DEFLATE it rather than writing them raw — NBT is
        // text-like and repetitive, so this compresses well and keeps the packet off the
        // per-connection rate limit under a large timeline.
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        body.writeVarInt(packet.commits.size());
        for (TemporalCommit commit : packet.commits) TemporalCommit.encode(commit, body);
        body.writeVarInt(packet.localParents.size());
        for (Map.Entry<Long, Long> entry : packet.localParents.entrySet()) {
            body.writeLong(entry.getKey());
            body.writeLong(entry.getValue());
        }
        body.writeVarInt(packet.jumpCosts.size());
        for (Map.Entry<Long, Long> entry : packet.jumpCosts.entrySet()) {
            body.writeLong(entry.getKey());
            body.writeLong(entry.getValue());
        }
        body.writeVarInt(packet.previewChunkSnapshots.size());
        for (ChunkTimelineSnapshot snapshot : packet.previewChunkSnapshots) ChunkTimelineSnapshot.encode(snapshot, body);
        body.writeVarInt(packet.gluedRegions.size());
        for (BoundingBox region : packet.gluedRegions) {
            body.writeInt(region.minX());
            body.writeInt(region.minY());
            body.writeInt(region.minZ());
            body.writeInt(region.maxX());
            body.writeInt(region.maxY());
            body.writeInt(region.maxZ());
        }

        byte[] rawBody = new byte[body.readableBytes()];
        body.readBytes(rawBody);
        body.release();

        buf.writeVarInt(rawBody.length);
        buf.writeByteArray(CompressionUtil.compress(rawBody));

        buf.writeLong(packet.headCommitId);
        buf.writeLong(packet.selectedCommitId);
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
        int size = body.readVarInt();
        List<TemporalCommit> commits = new ArrayList<>(size);
        for (int i = 0; i < size; i++) commits.add(TemporalCommit.decode(body));
        int localParentCount = body.readVarInt();
        Map<Long, Long> localParents = new HashMap<>();
        for (int i = 0; i < localParentCount; i++) {
            long commitId = body.readLong();
            long parentId = body.readLong();
            localParents.put(commitId, parentId);
        }
        int jumpCostCount = body.readVarInt();
        Map<Long, Long> jumpCosts = new HashMap<>();
        for (int i = 0; i < jumpCostCount; i++) {
            long commitId = body.readLong();
            long cost = body.readLong();
            jumpCosts.put(commitId, cost);
        }
        int previewChunkCount = body.readVarInt();
        List<ChunkTimelineSnapshot> previewChunkSnapshots = new ArrayList<>(previewChunkCount);
        for (int i = 0; i < previewChunkCount; i++) previewChunkSnapshots.add(ChunkTimelineSnapshot.decode(body));
        int gluedRegionCount = body.readVarInt();
        List<BoundingBox> gluedRegions = new ArrayList<>(gluedRegionCount);
        for (int i = 0; i < gluedRegionCount; i++) {
            gluedRegions.add(new BoundingBox(body.readInt(), body.readInt(), body.readInt(),
                    body.readInt(), body.readInt(), body.readInt()));
        }
        body.release();

        long headCommitId = buf.readLong();
        long selectedCommitId = buf.readLong();
        long previewVersion = buf.readLong();
        return new TimelinePreviewSyncPacket(machinePos, placedGameTime, selectedGameTime,
                currentGameTime, commits, localParents, headCommitId, selectedCommitId, jumpCosts, previewChunkSnapshots,
                gluedRegions, previewVersion);
    }

    public static void handle(TimelinePreviewSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> TimelineProjectionManager.updateFromServer(
                packet.machinePos,
                packet.placedGameTime,
                packet.currentGameTime,
                packet.commits,
                packet.localParents,
                packet.headCommitId,
                packet.jumpCosts,
                packet.previewChunkSnapshots,
                packet.gluedRegions,
                packet.previewVersion));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
