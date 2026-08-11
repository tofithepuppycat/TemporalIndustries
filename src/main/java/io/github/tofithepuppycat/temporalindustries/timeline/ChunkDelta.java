package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("null")
public final class ChunkDelta {
    private final ResourceLocation dimension;
    private final ChunkPos chunkPos;
    private final List<BlockChangeDelta> blockChanges;
    private final List<EntityDelta> entityChanges;

    public ChunkDelta(ResourceLocation dimension, ChunkPos chunkPos, List<BlockChangeDelta> blockChanges) {
        this(dimension, chunkPos, blockChanges, Collections.emptyList());
    }

    public ChunkDelta(ResourceLocation dimension, ChunkPos chunkPos, List<BlockChangeDelta> blockChanges,
                      List<EntityDelta> entityChanges) {
        this.dimension = dimension;
        this.chunkPos = chunkPos;
        this.blockChanges = new ArrayList<>(blockChanges);
        this.entityChanges = new ArrayList<>(entityChanges);
    }

    public ResourceLocation getDimension() { return dimension; }
    public ChunkPos getChunkPos() { return chunkPos; }
    public List<BlockChangeDelta> getBlockChanges() { return blockChanges; }
    public List<EntityDelta> getEntityChanges() { return entityChanges; }
    public int getChangeCount() { return blockChanges.size() + entityChanges.size(); }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.toString());
        tag.putInt("ChunkX", chunkPos.x);
        tag.putInt("ChunkZ", chunkPos.z);
        ListTag changes = new ListTag();
        for (BlockChangeDelta delta : blockChanges) changes.add(delta.toTag());
        tag.put("Changes", changes);
        ListTag entities = new ListTag();
        for (EntityDelta delta : entityChanges) entities.add(delta.toTag());
        tag.put("EntityChanges", entities);
        return tag;
    }

    public static ChunkDelta fromTag(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        ChunkPos chunkPos = new ChunkPos(tag.getInt("ChunkX"), tag.getInt("ChunkZ"));
        ListTag changeList = tag.getList("Changes", Tag.TAG_COMPOUND);
        List<BlockChangeDelta> changes = new ArrayList<>();
        for (int i = 0; i < changeList.size(); i++) {
            changes.add(BlockChangeDelta.fromTag(changeList.getCompound(i)));
        }
        List<EntityDelta> entities = new ArrayList<>();
        if (tag.contains("EntityChanges", Tag.TAG_LIST)) {
            ListTag entityList = tag.getList("EntityChanges", Tag.TAG_COMPOUND);
            for (int i = 0; i < entityList.size(); i++) {
                entities.add(EntityDelta.fromTag(entityList.getCompound(i)));
            }
        }
        return new ChunkDelta(dimension, chunkPos, changes, entities);
    }

    public static void encode(ChunkDelta delta, FriendlyByteBuf buf) {
        buf.writeResourceLocation(delta.dimension);
        buf.writeInt(delta.chunkPos.x);
        buf.writeInt(delta.chunkPos.z);
        buf.writeVarInt(delta.blockChanges.size());
        for (BlockChangeDelta change : delta.blockChanges) BlockChangeDelta.encode(change, buf);
        buf.writeVarInt(delta.entityChanges.size());
        for (EntityDelta change : delta.entityChanges) EntityDelta.encode(change, buf);
    }

    public static ChunkDelta decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        ChunkPos chunkPos = new ChunkPos(buf.readInt(), buf.readInt());
        int size = buf.readVarInt();
        List<BlockChangeDelta> changes = new ArrayList<>();
        for (int i = 0; i < size; i++) changes.add(BlockChangeDelta.decode(buf));
        int entitySize = buf.readVarInt();
        List<EntityDelta> entities = new ArrayList<>();
        for (int i = 0; i < entitySize; i++) entities.add(EntityDelta.decode(buf));
        return new ChunkDelta(dimension, chunkPos, changes, entities);
    }
}
