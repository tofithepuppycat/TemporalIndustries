package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A named, git-like ref into a {@link TemporalTimeline}'s commit graph: for every chunk it covers,
 * the commit that chunk should resolve to when this branch is checked out. Advanced automatically
 * by {@link TemporalTimeline} as new commits land while this branch is the one checked out, the
 * same way a real git branch pointer moves forward with each commit.
 */
public final class TemporalBranch {
    private final String name;
    /** chunkPos.toLong() -> the commit id this branch points to for that chunk. */
    private final Map<Long, Long> chunkHeads;

    public TemporalBranch(String name, Map<Long, Long> chunkHeads) {
        this.name = name;
        this.chunkHeads = new HashMap<>(chunkHeads);
    }

    public String getName() { return name; }

    public Map<Long, Long> getChunkHeads() { return Collections.unmodifiableMap(chunkHeads); }

    public long getChunkHead(ChunkPos pos) { return chunkHeads.getOrDefault(pos.toLong(), -1L); }

    public void setChunkHead(ChunkPos pos, long commitId) { chunkHeads.put(pos.toLong(), commitId); }

    public void removeChunkHead(ChunkPos pos) { chunkHeads.remove(pos.toLong()); }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        ListTag heads = new ListTag();
        for (Map.Entry<Long, Long> entry : chunkHeads.entrySet()) {
            CompoundTag headTag = new CompoundTag();
            headTag.putLong("Chunk", entry.getKey());
            headTag.putLong("Head", entry.getValue());
            heads.add(headTag);
        }
        tag.put("ChunkHeads", heads);
        return tag;
    }

    public static TemporalBranch fromTag(CompoundTag tag) {
        Map<Long, Long> chunkHeads = new HashMap<>();
        ListTag heads = tag.getList("ChunkHeads", Tag.TAG_COMPOUND);
        for (int i = 0; i < heads.size(); i++) {
            CompoundTag headTag = heads.getCompound(i);
            chunkHeads.put(headTag.getLong("Chunk"), headTag.getLong("Head"));
        }
        return new TemporalBranch(tag.getString("Name"), chunkHeads);
    }
}
