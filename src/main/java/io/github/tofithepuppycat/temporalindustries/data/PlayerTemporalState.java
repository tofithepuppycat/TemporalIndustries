package io.github.tofithepuppycat.temporalindustries.data;

import io.github.tofithepuppycat.temporalindustries.device.PlayerSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.BlockChangeDelta;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Server-side temporal state for one player: their checkpoint snapshot and every block change
 * recorded since calibration.
 */
@SuppressWarnings("null")
public class PlayerTemporalState {
    private final UUID playerId;
    @Nullable private PlayerSnapshot checkpoint;
    private static final double DROP_SEARCH_RADIUS = 3.0;

    private record ChangeKey(ResourceLocation dimension, BlockPos pos) {}

    // The earliest recorded change per position, in the order the positions were first touched.
    private final Map<ChangeKey, PlayerBlockChange> changes = new LinkedHashMap<>();
    @Nullable private ChronoRiftOrigin chronoRiftOrigin;

    /**
     * Where a player stood right before stepping into the Chrono Rift, so the Chronoscope's return
     * trip can put them back without touching health/inventory/xp (unlike the heavier {@link
     * PlayerSnapshot}, which a rift round-trip has no reason to rewind).
     */
    public record ChronoRiftOrigin(ResourceLocation dimension, double x, double y, double z, float yRot, float xRot) {
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", dimension.toString());
            tag.putDouble("X", x);
            tag.putDouble("Y", y);
            tag.putDouble("Z", z);
            tag.putFloat("YRot", yRot);
            tag.putFloat("XRot", xRot);
            return tag;
        }

        @Nullable
        public static ChronoRiftOrigin fromTag(CompoundTag tag) {
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
            if (dimension == null) return null;
            return new ChronoRiftOrigin(dimension,
                    tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"),
                    tag.getFloat("YRot"), tag.getFloat("XRot"));
        }
    }

    public record PlayerBlockChange(ResourceLocation dimension, BlockChangeDelta delta) {
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", dimension.toString());
            tag.put("Delta", delta.toTag());
            return tag;
        }

        public static PlayerBlockChange fromTag(CompoundTag tag) {
            ResourceLocation dim = ResourceLocation.tryParse(tag.getString("Dimension"));
            BlockChangeDelta delta = BlockChangeDelta.fromTag(tag.getCompound("Delta"));
            return new PlayerBlockChange(dim, delta);
        }
    }

    public PlayerTemporalState(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() { return playerId; }

    public boolean isArmed() { return checkpoint != null; }

    @Nullable
    public PlayerSnapshot getCheckpoint() { return checkpoint; }

    public void setCheckpoint(PlayerSnapshot snapshot) {
        this.checkpoint = snapshot;
        this.changes.clear();
    }

    @Nullable
    public ChronoRiftOrigin getChronoRiftOrigin() {
        return chronoRiftOrigin;
    }

    public void setChronoRiftOrigin(ChronoRiftOrigin origin) {
        this.chronoRiftOrigin = origin;
    }

    public void clearChronoRiftOrigin() {
        this.chronoRiftOrigin = null;
    }

    /**
     * Records a change; only the earliest change per position matters for the revert, so later
     * ones at the same position are dropped rather than accumulated.
     */
    public void recordChange(ResourceLocation dimension, BlockChangeDelta delta) {
        if (checkpoint == null) return;
        changes.putIfAbsent(new ChangeKey(dimension, delta.getPos()), new PlayerBlockChange(dimension, delta));
    }

    /**
     * Restores every tracked block to its state at the last checkpoint, then clears the change list.
     */
    public int revertWorldChanges(MinecraftServer server) {
        int reverted = 0;
        for (PlayerBlockChange change : changes.values()) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, change.dimension()));
            if (level == null) continue;

            BlockChangeDelta delta = change.delta();
            BlockPos pos = delta.getPos();
            level.setBlock(pos, delta.getPreviousState(), 3);

            CompoundTag prevBE = delta.getPreviousBlockEntityTag();
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null && prevBE != null) {
                CompoundTag restored = prevBE.copy();
                restored.putInt("x", pos.getX());
                restored.putInt("y", pos.getY());
                restored.putInt("z", pos.getZ());
                be.loadWithComponents(restored, level.registryAccess());
                be.setChanged();
                if (be instanceof Container container) removeDroppedContents(level, pos, container);
            }
            reverted++;
        }

        changes.clear();
        return reverted;
    }

    /**
     * A container restored with its contents gets them back from the items it dropped when it was
     * broken, so matching item entities lying near it are taken away.
     */
    private static void removeDroppedContents(ServerLevel level, BlockPos pos, Container container) {
        List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(DROP_SEARCH_RADIUS));
        if (dropped.isEmpty()) return;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack content = container.getItem(slot);
            int remaining = content.getCount();
            for (ItemEntity entity : dropped) {
                if (remaining <= 0) break;
                if (entity.isRemoved() || !ItemStack.isSameItemSameComponents(entity.getItem(), content)) continue;
                int taken = Math.min(remaining, entity.getItem().getCount());
                entity.getItem().shrink(taken);
                if (entity.getItem().isEmpty()) entity.discard();
                remaining -= taken;
            }
        }
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerId", playerId);
        if (checkpoint != null) tag.put("Checkpoint", checkpoint.toTag());
        ListTag changeList = new ListTag();
        for (PlayerBlockChange pbc : changes.values()) changeList.add(pbc.toTag());
        tag.put("Changes", changeList);
        if (chronoRiftOrigin != null) tag.put("ChronoRiftOrigin", chronoRiftOrigin.toTag());
        return tag;
    }

    public static PlayerTemporalState fromTag(CompoundTag tag) {
        UUID playerId = tag.getUUID("PlayerId");
        PlayerTemporalState state = new PlayerTemporalState(playerId);
        if (tag.contains("Checkpoint", Tag.TAG_COMPOUND)) {
            state.checkpoint = PlayerSnapshot.fromTag(tag.getCompound("Checkpoint"));
        }
        ListTag changeList = tag.getList("Changes", Tag.TAG_COMPOUND);
        for (int i = 0; i < changeList.size(); i++) {
            PlayerBlockChange change = PlayerBlockChange.fromTag(changeList.getCompound(i));
            if (change.dimension() == null) continue;
            state.changes.putIfAbsent(new ChangeKey(change.dimension(), change.delta().getPos()), change);
        }
        if (tag.contains("ChronoRiftOrigin", Tag.TAG_COMPOUND)) {
            state.chronoRiftOrigin = ChronoRiftOrigin.fromTag(tag.getCompound("ChronoRiftOrigin"));
        }
        return state;
    }
}
