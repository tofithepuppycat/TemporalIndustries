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
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Server-side temporal state for one player: their checkpoint snapshot and every
 * block change recorded since calibration. Stored in TemporalWorldData keyed by
 * player UUID — no state lives on the item itself.
 */
@SuppressWarnings("null")
public class PlayerTemporalState {
    private final UUID playerId;
    @Nullable private PlayerSnapshot checkpoint;
    private final List<PlayerBlockChange> changes = new ArrayList<>();

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

    public void clearCheckpoint() {
        checkpoint = null;
        changes.clear();
    }

    public void recordChange(ResourceLocation dimension, BlockChangeDelta delta) {
        if (checkpoint == null) return;
        changes.add(new PlayerBlockChange(dimension, delta));
    }

    /**
     * Restores every tracked block to the state it had at the last checkpoint.
     * Uses the earliest recorded change per position (the state before the player
     * first touched it), then clears the change list.
     *
     * @return the number of distinct block positions reverted
     */
    public int revertWorldChanges(MinecraftServer server) {
        Map<ResourceLocation, Map<BlockPos, BlockChangeDelta>> earliestByDim = new LinkedHashMap<>();
        for (PlayerBlockChange pbc : changes) {
            earliestByDim.computeIfAbsent(pbc.dimension(), d -> new LinkedHashMap<>())
                         .putIfAbsent(pbc.delta().getPos(), pbc.delta());
        }

        int reverted = 0;
        for (Map.Entry<ResourceLocation, Map<BlockPos, BlockChangeDelta>> dimEntry : earliestByDim.entrySet()) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimEntry.getKey()));
            if (level == null) continue;

            for (BlockChangeDelta delta : dimEntry.getValue().values()) {
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
                }
                reverted++;
            }
        }

        changes.clear();
        return reverted;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerId", playerId);
        if (checkpoint != null) tag.put("Checkpoint", checkpoint.toTag());
        ListTag changeList = new ListTag();
        for (PlayerBlockChange pbc : changes) changeList.add(pbc.toTag());
        tag.put("Changes", changeList);
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
            state.changes.add(PlayerBlockChange.fromTag(changeList.getCompound(i)));
        }
        return state;
    }
}
