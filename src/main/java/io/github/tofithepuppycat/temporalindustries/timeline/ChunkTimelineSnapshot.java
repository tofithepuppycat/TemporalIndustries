package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One chunk's commit graph, bundled with its ChunkPos so a client can resolve that chunk's own
 * ghost-preview diff independently of whichever chunk {@link io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget}
 * happens to be displaying. A Time Machine only ever has one of these (its own chunk); a
 * Chronosphere has one per currently claimed chunk, since jumping moves all of them together —
 * see {@link io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider#getPreviewChunkSnapshots()}.
 */
public record ChunkTimelineSnapshot(ChunkPos chunkPos, List<TemporalCommit> commits,
                                    Map<Long, Long> localParents, long headId) {

    public static void encode(ChunkTimelineSnapshot snapshot, FriendlyByteBuf buf) {
        buf.writeInt(snapshot.chunkPos.x);
        buf.writeInt(snapshot.chunkPos.z);
        buf.writeVarInt(snapshot.commits.size());
        for (TemporalCommit commit : snapshot.commits) TemporalCommit.encode(commit, buf);
        buf.writeVarInt(snapshot.localParents.size());
        for (Map.Entry<Long, Long> entry : snapshot.localParents.entrySet()) {
            buf.writeLong(entry.getKey());
            buf.writeLong(entry.getValue());
        }
        buf.writeLong(snapshot.headId);
    }

    public static ChunkTimelineSnapshot decode(FriendlyByteBuf buf) {
        ChunkPos chunkPos = new ChunkPos(buf.readInt(), buf.readInt());
        int commitCount = buf.readVarInt();
        List<TemporalCommit> commits = new ArrayList<>(commitCount);
        for (int i = 0; i < commitCount; i++) commits.add(TemporalCommit.decode(buf));
        int parentCount = buf.readVarInt();
        Map<Long, Long> localParents = new HashMap<>();
        for (int i = 0; i < parentCount; i++) {
            long commitId = buf.readLong();
            long parentId = buf.readLong();
            localParents.put(commitId, parentId);
        }
        long headId = buf.readLong();
        return new ChunkTimelineSnapshot(chunkPos, commits, localParents, headId);
    }
}
