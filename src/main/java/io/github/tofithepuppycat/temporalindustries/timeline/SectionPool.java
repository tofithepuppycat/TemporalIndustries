package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sections of every snapshot in a repository, each stored once. Snapshots of the same chunk are
 * mostly the same sections over and over (a claim baseline and the first full snapshot after it, a
 * chain re-cut that touched one corner), and a snapshot written through the pool is only a list of
 * indices into it.
 */
final class SectionPool {
    private final IdentityHashMap<ChunkSnapshot.SnapshotSection, Integer> bySection = new IdentityHashMap<>();
    private final Map<BlockState, Integer> byUniformState = new HashMap<>();
    private final ListTag tags = new ListTag();

    /** The index of section in the pool, adding it the first time it is seen. */
    int intern(ChunkSnapshot.SnapshotSection section) {
        BlockState uniform = section.uniformStateOrNull();
        Integer known = uniform != null ? byUniformState.get(uniform) : bySection.get(section);
        if (known != null) return known;

        int index = tags.size();
        tags.add(section.toTag());
        if (uniform != null) byUniformState.put(uniform, index);
        else bySection.put(section, index);
        return index;
    }

    ListTag toTag() {
        return tags;
    }

    static List<ChunkSnapshot.SnapshotSection> fromTag(ListTag tag) {
        List<ChunkSnapshot.SnapshotSection> sections = new ArrayList<>(tag.size());
        for (int i = 0; i < tag.size(); i++) {
            CompoundTag sectionTag = tag.getCompound(i);
            sections.add(ChunkSnapshot.SnapshotSection.fromTag(sectionTag));
        }
        return sections;
    }
}
