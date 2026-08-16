package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.menu.TimeMachineMenu;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkTimelineSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Block entity for the Time Machine: holds per-machine energy/time state and reads/writes
 * the shared {@link TemporalTimeline} for its chunk. See {@link AbstractTimelineMachineBlockEntity}
 * for the logic shared with {@link ChronosphereBlockEntity} — a Time Machine is just a Chronosphere
 * whose jumps only ever move its own single chunk. */
public class TimeMachineBlockEntity extends AbstractTimelineMachineBlockEntity {
    private static final int ENERGY_CAPACITY = 100_000;
    private static final int ENERGY_TRANSFER  = 1_000;

    public TimeMachineBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(Registration.TIME_MACHINE_BLOCK_ENTITY.get(), blockPos, blockState, ENERGY_CAPACITY, ENERGY_TRANSFER);
    }

    private ChunkPos getChunkPos() {
        return new ChunkPos(worldPosition);
    }

    @Override
    public List<ChunkPos> getAllChunks() {
        return List.of(getChunkPos());
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    @Override
    public void onLoad() {
        super.onLoad();

        if (level instanceof ServerLevel serverLevel) {
            if (serverLevel.getServer() != null) {
                TemporalWorldData worldData = TemporalWorldData.get(serverLevel.getServer());
                ChunkPos chunkPos = getChunkPos();
                ResourceLocation dimension = level.dimension().location();
                worldData.trackChunk(dimension, chunkPos, worldPosition, serverLevel);

                TemporalTimeline timeline = worldData.getOrCreateTimeline(dimension);

                // Restore placedGameTime from the timeline if this is a reload
                if (placedGameTime == UNSET_TIME) {
                    long earliest = timeline.getEarliestGameTimeForChunk(chunkPos);
                    if (earliest != -1L) {
                        placedGameTime  = earliest;
                        selectedGameTime = Math.max(placedGameTime, selectedGameTime == UNSET_TIME ? placedGameTime : selectedGameTime);
                        setChanged();
                    }
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide && level.getServer() != null) {
            TemporalWorldData.get(level.getServer())
                    .untrackChunk(level.dimension().location(), getChunkPos(), worldPosition);
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TimeMachineBlockEntity be) {
        if (level.isClientSide) return;
        be.commonTick(level);
    }

    // -------------------------------------------------------------------------
    // Timeline access (delegates to TemporalWorldData)

    /**
     * Returns all commits relevant to this machine's chunk (commits touching it, plus its own
     * branch markers), in chronological order. Used by the sync packet to send timeline data
     * to the client. This machine only ever has one chunk, so chunkPos is ignored.
     */
    @Override
    public List<TemporalCommit> getChunkCommits(@Nullable ChunkPos chunkPos) {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyList();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyList();
        return timeline.getCommitsForChunk(getChunkPos());
    }

    /**
     * Returns commitId -> the id it locally forked from within this machine's chunk (see
     * TemporalTimeline's class doc). Sent alongside getChunkCommits() so the client can lay out
     * the timeline graph using this chunk's real branch topology instead of guessing from order.
     */
    @Override
    public Map<Long, Long> getChunkLocalParents(@Nullable ChunkPos chunkPos) {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyMap();
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return Collections.emptyMap();
        return timeline.getLocalParentsForChunk(getChunkPos());
    }

    /** The commit this machine's chunk's live world currently reflects. Not always the last entry
     * in getChunkCommits() — see {@link TemporalTimeline#branch}. */
    @Override
    public long getChunkHeadId(@Nullable ChunkPos chunkPos) {
        if (level == null || level.isClientSide || level.getServer() == null) return -1L;
        TemporalTimeline timeline = TemporalWorldData.get(level.getServer())
                .getTimeline(level.dimension().location());
        if (timeline == null) return -1L;
        return timeline.getChunkHeadId(getChunkPos());
    }

    /** commitId -> energy cost of jumping this machine's chunk there from its current head, for
     * every commit relevant to this chunk. Used to preview cost per node in the timeline GUI. */
    @Override
    public Map<Long, Long> getChunkJumpCosts(@Nullable ChunkPos chunkPos) {
        if (level == null || level.isClientSide || level.getServer() == null) return Collections.emptyMap();
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        TemporalTimeline timeline = worldData.getTimeline(dimension);
        if (timeline == null) return Collections.emptyMap();

        ChunkPos ownChunk = getChunkPos();
        long headId = timeline.getChunkHeadId(ownChunk);
        Map<Long, Long> costs = new HashMap<>();
        for (TemporalCommit commit : timeline.getCommitsForChunk(ownChunk)) {
            costs.put(commit.getId(), timeline.computeJumpCost(ownChunk, commit.getGameTime(), headId, level,
                    AbstractTimelineMachineBlockEntity::costOf, commit.getId(), pos -> worldData.isGlued(dimension, pos)));
        }
        return costs;
    }

    @Override
    public List<ChunkTimelineSnapshot> getPreviewChunkSnapshots() {
        ChunkPos chunkPos = getChunkPos();
        return List.of(new ChunkTimelineSnapshot(chunkPos, getChunkCommits(chunkPos), getChunkLocalParents(chunkPos), getChunkHeadId(chunkPos)));
    }

    // -------------------------------------------------------------------------
    // MenuProvider

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.temporalindustries.time_machine");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory playerInventory, @NotNull Player player) {
        return new TimeMachineMenu(id, playerInventory, this,
                ContainerLevelAccess.create(Objects.requireNonNull(level), worldPosition), getContainerData());
    }
}
