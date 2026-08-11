package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class EntityDelta {
    public enum Type { SPAWNED, REMOVED, MODIFIED }

    private final UUID entityId;
    private final Type type;
    @Nullable private final CompoundTag stateBefore;
    @Nullable private final CompoundTag stateAfter;

    public EntityDelta(UUID entityId, Type type,
                       @Nullable CompoundTag stateBefore,
                       @Nullable CompoundTag stateAfter) {
        this.entityId = entityId;
        this.type = type;
        this.stateBefore = stateBefore == null ? null : stateBefore.copy();
        this.stateAfter = stateAfter == null ? null : stateAfter.copy();
    }

    public UUID getEntityId() { return entityId; }
    public Type getType() { return type; }

    @Nullable
    public CompoundTag getStateBefore() {
        return stateBefore == null ? null : stateBefore.copy();
    }

    @Nullable
    public CompoundTag getStateAfter() {
        return stateAfter == null ? null : stateAfter.copy();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("EntityId", entityId);
        tag.putInt("Type", type.ordinal());
        if (stateBefore != null) tag.put("Before", stateBefore.copy());
        if (stateAfter != null) tag.put("After", stateAfter.copy());
        return tag;
    }

    public static EntityDelta fromTag(CompoundTag tag) {
        UUID entityId = tag.getUUID("EntityId");
        Type type = Type.values()[tag.getInt("Type")];
        CompoundTag before = tag.contains("Before", Tag.TAG_COMPOUND) ? tag.getCompound("Before") : null;
        CompoundTag after = tag.contains("After", Tag.TAG_COMPOUND) ? tag.getCompound("After") : null;
        return new EntityDelta(entityId, type, before, after);
    }

    public static void encode(EntityDelta delta, FriendlyByteBuf buf) {
        buf.writeUUID(delta.entityId);
        buf.writeVarInt(delta.type.ordinal());
        buf.writeBoolean(delta.stateBefore != null);
        if (delta.stateBefore != null) buf.writeNbt(delta.stateBefore);
        buf.writeBoolean(delta.stateAfter != null);
        if (delta.stateAfter != null) buf.writeNbt(delta.stateAfter);
    }

    public static EntityDelta decode(FriendlyByteBuf buf) {
        UUID entityId = buf.readUUID();
        Type type = Type.values()[buf.readVarInt()];
        CompoundTag before = buf.readBoolean() ? buf.readNbt() : null;
        CompoundTag after = buf.readBoolean() ? buf.readNbt() : null;
        return new EntityDelta(entityId, type, before, after);
    }
}
