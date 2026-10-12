package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The wire/client-side projection of a {@link RepoCommit}: just the metadata the timeline graph
 * needs to lay out and label a node.
 */
public record CommitNode(long id, long parentId, long gameTime, String shortHash,
                         boolean authored, int chunkCount, int blockChangeCount, boolean hasFullBaseline) {

    public static CommitNode of(RepoCommit commit) {
        int blockChanges = 0;
        boolean hasFull = false;
        for (ChunkEntry entry : commit.getChunkEntries().values()) {
            if (entry.getType() == ChunkEntry.Type.FULL) {
                hasFull = true;
            } else {
                blockChanges += entry.getBlockChanges().size() + entry.getEntityChanges().size();
            }
        }
        return new CommitNode(commit.getId(), commit.getParentId(), commit.getGameTime(), commit.getShortHash(),
                commit.getAuthor() != null, commit.getChunkEntries().size(), blockChanges, hasFull);
    }

    public static void encode(CommitNode node, FriendlyByteBuf buf) {
        buf.writeLong(node.id);
        buf.writeLong(node.parentId);
        buf.writeLong(node.gameTime);
        buf.writeUtf(node.shortHash, 16);
        buf.writeBoolean(node.authored);
        buf.writeVarInt(node.chunkCount);
        buf.writeVarInt(node.blockChangeCount);
        buf.writeBoolean(node.hasFullBaseline);
    }

    public static CommitNode decode(FriendlyByteBuf buf) {
        return new CommitNode(buf.readLong(), buf.readLong(), buf.readLong(), buf.readUtf(16),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }
}
