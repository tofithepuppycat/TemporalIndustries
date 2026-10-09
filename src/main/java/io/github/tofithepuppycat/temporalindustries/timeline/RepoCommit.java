package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One node in a {@link TemporalRepository}'s commit tree. Each commit has a single parent (-1 for
 * the repository's root) and carries, per chunk it touches, either a full baseline or a delta (see
 * {@link ChunkEntry}) — chunks it doesn't touch simply aren't present in {@link #getChunkEntries()}.
 */
public final class RepoCommit {
    public static final long NO_PARENT = -1L;

    private final long id;
    private final long parentId;
    private final long gameTime;
    @Nullable private final UUID author;
    private final Map<Long, ChunkEntry> chunkEntries;

    public RepoCommit(long id, long parentId, long gameTime, @Nullable UUID author, Map<Long, ChunkEntry> chunkEntries) {
        this.id = id;
        this.parentId = parentId;
        this.gameTime = gameTime;
        this.author = author;
        this.chunkEntries = Collections.unmodifiableMap(new HashMap<>(chunkEntries));
    }

    public long getId() { return id; }
    public long getParentId() { return parentId; }
    public long getGameTime() { return gameTime; }
    @Nullable public UUID getAuthor() { return author; }
    public Map<Long, ChunkEntry> getChunkEntries() { return chunkEntries; }

    /** Deterministic 7-hex-digit label derived from this commit's identity, styled after a git short hash. */
    public String getShortHash() {
        long h = id * 0x9E3779B97F4A7C15L;
        h ^= Long.rotateLeft(parentId * 0xC2B2AE3D27D4EB4FL + 1L, 17);
        h ^= gameTime * 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return String.format("%07x", h & 0xFFFFFFFL);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putLong("ParentId", parentId);
        tag.putLong("GameTime", gameTime);
        if (author != null) tag.putUUID("Author", author);

        ListTag entries = new ListTag();
        for (Map.Entry<Long, ChunkEntry> entry : chunkEntries.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Chunk", entry.getKey());
            entryTag.put("Entry", entry.getValue().toTag());
            entries.add(entryTag);
        }
        tag.put("ChunkEntries", entries);
        return tag;
    }

    public static RepoCommit fromTag(CompoundTag tag) {
        long id = tag.getLong("Id");
        long parentId = tag.getLong("ParentId");
        long gameTime = tag.getLong("GameTime");
        UUID author = tag.hasUUID("Author") ? tag.getUUID("Author") : null;

        Map<Long, ChunkEntry> chunkEntries = new HashMap<>();
        ListTag entries = tag.getList("ChunkEntries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entryTag = entries.getCompound(i);
            chunkEntries.put(entryTag.getLong("Chunk"), ChunkEntry.fromTag(entryTag.getCompound("Entry")));
        }

        return new RepoCommit(id, parentId, gameTime, author, chunkEntries);
    }
}
