package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One chunk's contribution to a {@link RepoCommit}: either a FULL baseline ({@link ChunkSnapshot})
 * or a DELTA (only what changed since that chunk's previous entry). A single commit can mix both
 * kinds across its chunks — each chunk's own history decides which one it needs independently of
 * its neighbors (see {@link TemporalRepository}).
 */
public final class ChunkEntry {
    public enum Type { FULL, DELTA }

    private final Type type;
    private final ChunkSnapshot snapshot;
    private final List<BlockChangeDelta> blockChanges;
    private final List<EntityDelta> entityChanges;

    private ChunkEntry(Type type, ChunkSnapshot snapshot, List<BlockChangeDelta> blockChanges, List<EntityDelta> entityChanges) {
        this.type = type;
        this.snapshot = snapshot;
        this.blockChanges = Collections.unmodifiableList(new ArrayList<>(blockChanges));
        this.entityChanges = Collections.unmodifiableList(new ArrayList<>(entityChanges));
    }

    public static ChunkEntry full(ChunkSnapshot snapshot) {
        return new ChunkEntry(Type.FULL, snapshot, Collections.emptyList(), Collections.emptyList());
    }

    public static ChunkEntry delta(List<BlockChangeDelta> blockChanges, List<EntityDelta> entityChanges) {
        return new ChunkEntry(Type.DELTA, null, blockChanges, entityChanges);
    }

    public Type getType() { return type; }

    /** Only meaningful when {@link #getType()} == {@link Type#FULL}. */
    public ChunkSnapshot getSnapshot() { return snapshot; }

    /** Only meaningful when {@link #getType()} == {@link Type#DELTA}. */
    public List<BlockChangeDelta> getBlockChanges() { return blockChanges; }

    /** Only meaningful when {@link #getType()} == {@link Type#DELTA}. */
    public List<EntityDelta> getEntityChanges() { return entityChanges; }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Type", type.ordinal());
        if (type == Type.FULL) {
            tag.put("Snapshot", snapshot.toTag());
        } else {
            ListTag changes = new ListTag();
            for (BlockChangeDelta delta : blockChanges) changes.add(delta.toTag());
            tag.put("Changes", changes);
            ListTag entities = new ListTag();
            for (EntityDelta delta : entityChanges) entities.add(delta.toTag());
            tag.put("EntityChanges", entities);
        }
        return tag;
    }

    public static ChunkEntry fromTag(CompoundTag tag) {
        Type type = Type.values()[tag.getInt("Type")];
        if (type == Type.FULL) {
            return full(ChunkSnapshot.fromTag(tag.getCompound("Snapshot")));
        }

        List<BlockChangeDelta> changes = new ArrayList<>();
        ListTag changeList = tag.getList("Changes", Tag.TAG_COMPOUND);
        for (int i = 0; i < changeList.size(); i++) changes.add(BlockChangeDelta.fromTag(changeList.getCompound(i)));

        List<EntityDelta> entities = new ArrayList<>();
        ListTag entityList = tag.getList("EntityChanges", Tag.TAG_COMPOUND);
        for (int i = 0; i < entityList.size(); i++) entities.add(EntityDelta.fromTag(entityList.getCompound(i)));

        return delta(changes, entities);
    }
}
