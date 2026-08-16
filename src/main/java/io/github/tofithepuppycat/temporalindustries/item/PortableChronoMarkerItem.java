package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handheld player save-point tool. Right-click captures a fresh {@link ChunkSnapshot} of every
 * chunk in a radius around the wielder and diffs it against that chunk's current timeline head
 * (see {@link TemporalTimeline#diffChunkAgainstHead}), committing the result as an ordinary DELTA
 * — or, once the radius has drifted far enough from its last baseline, a fresh SNAPSHOT instead
 * (see {@link TemporalTimeline#SNAPSHOT_COMMIT_THRESHOLD}) — the exact same commit types
 * auto-tracking produces on its own, so a player's manual save point is restorable from any Time
 * Machine/Chronosphere viewing that chunk exactly like an automatically tracked one. Unlike a
 * placed machine, the marker never registers continuous background tracking ({@link
 * TemporalWorldData#trackChunk}): it only ever touches the timeline at the moment of a save, from
 * two point-in-time captures, not from listening to every block change as it happens. A zero-diff
 * SAVE_MARKER commit rides alongside it purely so the graph can point out where the player
 * actually saved (see {@link TemporalTimeline#addSaveMarker}).
 *
 * <p>A plain right-click instantly marks the default square radius around the player. Sneak +
 * right-click instead opens {@link io.github.tofithepuppycat.temporalindustries.client.screen.ChronoMarkerMapScreen},
 * the same chunk-selection map the Chronosphere's claim overlay uses (see {@link
 * io.github.tofithepuppycat.temporalindustries.client.chunkmap.ChunkSelectionGrid}), letting the
 * player pick exactly which chunks around them get captured before confirming.
 */
@SuppressWarnings("null")
public class PortableChronoMarkerItem extends Item {
    private static final int RADIUS_CHUNKS = 2;
    /** Radius (in chunks) offered by the sneak-right-click area-select map — shares the same
     * circular shape math as the Chronosphere's claim map (see {@link
     * io.github.tofithepuppycat.temporalindustries.chronomap.ChunkArea}), unlike the plain
     * right-click's square default radius above. */
    public static final int MAP_RADIUS_CHUNKS = 2;
    private static final int WAVE_DURATION_TICKS = 15;
    private static final double RING_SPEED = 6.0D;

    private static final DustParticleOptions WALL_PARTICLE = new DustParticleOptions(new Vector3f(1.0F, 1.0F, 1.0F), 1.0F);
    private static final DustParticleOptions WAVE_PARTICLE = new DustParticleOptions(new Vector3f(1.0F, 1.0F, 1.0F), 1.4F);

    /** playerId -> gameTime the current terrain-tracing wave started, consumed in inventoryTick.
     * Purely cosmetic — never persisted. */
    private static final Map<UUID, Long> ACTIVE_WAVE_START = new HashMap<>();

    public PortableChronoMarkerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                io.github.tofithepuppycat.temporalindustries.client.screen.ChronoMarkerMapScreen.open(new ChunkPos(player.blockPosition()));
            }
            return InteractionResultHolder.success(stack);
        }

        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }

        recordMark(serverPlayer, (ServerLevel) level, chunksInRadius(serverPlayer));
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!isSelected || !(level instanceof ServerLevel serverLevel) || !(entity instanceof ServerPlayer player)) return;

        spawnBoundaryWall(player, serverLevel);
        spawnWave(player, serverLevel);
    }

    // -------------------------------------------------------------------------
    // Marking

    /** Records a save point across {@code chunks} purely from two point-in-time captures — no
     * continuous background tracking involved. For each chunk: ensures it has a baseline (first
     * time it's ever marked), then either diffs a fresh {@link ChunkSnapshot} against that chunk's
     * current timeline head to produce an ordinary DELTA (see
     * {@link TemporalTimeline#diffChunkAgainstHead}), or, once the radius has drifted far enough
     * past its last baseline, re-snapshots it fresh instead — the exact same DELTA/SNAPSHOT commit
     * split (see {@link TemporalTimeline#SNAPSHOT_COMMIT_THRESHOLD}) auto-tracking produces on its
     * own. Called both for the plain right-click's fixed square radius and for the sneak-right-click
     * map screen's player-chosen selection — see {@link
     * io.github.tofithepuppycat.temporalindustries.network.ChronoMarkerMarkPacket}. */
    public static void recordMark(ServerPlayer player, ServerLevel level, List<ChunkPos> chunks) {
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        TemporalTimeline timeline = worldData.getOrCreateTimeline(dimension);

        List<ChunkDelta> chunkDeltas = new ArrayList<>();
        List<ChunkSnapshot> chunkSnapshots = new ArrayList<>();

        for (ChunkPos chunkPos : chunks) {
            // A chunk marked for the first time gets its baseline captured here, matching the live
            // world exactly — nothing to diff yet, so it's skipped rather than double-counted.
            if (timeline.ensureBaseline(chunkPos, level)) continue;

            ChunkSnapshot current = ChunkSnapshot.capture(level, chunkPos);
            if (timeline.getCommitsSinceSnapshot(chunkPos) >= TemporalTimeline.SNAPSHOT_COMMIT_THRESHOLD) {
                chunkSnapshots.add(current);
                continue;
            }

            ChunkDelta delta = timeline.diffChunkAgainstHead(dimension, current);
            if (delta != null) chunkDeltas.add(delta);
        }

        if (!chunkDeltas.isEmpty()) timeline.addDelta(level.getGameTime(), chunkDeltas);
        if (!chunkSnapshots.isEmpty()) timeline.addSnapshot(level.getGameTime(), chunkSnapshots);

        // Purely cosmetic: flags where this save actually happened on the graph (see
        // TimelineGraphWidget's diamond rendering) — the save itself is already captured by the
        // DELTA/SNAPSHOT commit(s) above.
        timeline.addSaveMarker(level.getGameTime(), chunks);
        worldData.setDirty();

        player.displayClientMessage(Component.translatable("item.temporalindustries.portable_chrono_marker.marked"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.2F);
        ACTIVE_WAVE_START.put(player.getUUID(), level.getGameTime());
    }

    // -------------------------------------------------------------------------
    // Radius

    private static List<ChunkPos> chunksInRadius(ServerPlayer player) {
        ChunkPos center = new ChunkPos(player.blockPosition());
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                chunks.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        return chunks;
    }

    // -------------------------------------------------------------------------
    // Visuals

    /** Sparse particle grid along the edges of the capture square, so the player can see where
     * tracking currently ends. */
    private static void spawnBoundaryWall(ServerPlayer player, ServerLevel level) {
        if (level.getGameTime() % 4 != 0) return;

        int radiusBlocks = RADIUS_CHUNKS * 16;
        int centerX = player.getBlockX();
        int centerZ = player.getBlockZ();
        double baseY = player.getY();

        for (int offset = -radiusBlocks; offset <= radiusBlocks; offset += 4) {
            spawnWallColumn(level, centerX + offset, centerZ - radiusBlocks, baseY);
            spawnWallColumn(level, centerX + offset, centerZ + radiusBlocks, baseY);
            spawnWallColumn(level, centerX - radiusBlocks, centerZ + offset, baseY);
            spawnWallColumn(level, centerX + radiusBlocks, centerZ + offset, baseY);
        }
    }

    private static void spawnWallColumn(ServerLevel level, int x, int z, double centerY) {
        for (double dy = -3.0D; dy <= 3.0D; dy += 1.5D) {
            level.sendParticles(WALL_PARTICLE, x + 0.5D, centerY + dy, z + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** Expanding ring of particles tracing the terrain surface outward from the player, signalling
     * a mark was just recorded. Consumes ACTIVE_WAVE_START, advancing once per tick this item is
     * held & selected, until the ring reaches the capture radius. */
    private static void spawnWave(ServerPlayer player, ServerLevel level) {
        Long start = ACTIVE_WAVE_START.get(player.getUUID());
        if (start == null) return;

        long elapsed = level.getGameTime() - start;
        double radius = elapsed * RING_SPEED;
        double maxRadius = RADIUS_CHUNKS * 16.0D;
        if (elapsed > WAVE_DURATION_TICKS || radius > maxRadius) {
            ACTIVE_WAVE_START.remove(player.getUUID());
            return;
        }

        int centerX = player.getBlockX();
        int centerZ = player.getBlockZ();
        int samples = Math.max(8, (int) (radius * 0.5D));
        for (int i = 0; i < samples; i++) {
            double angle = 2.0D * Math.PI * i / samples;
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int z = centerZ + (int) Math.round(Math.sin(angle) * radius);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            level.sendParticles(WAVE_PARTICLE, x + 0.5D, y + 0.2D, z + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.temporalindustries.portable_chrono_marker.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.temporalindustries.portable_chrono_marker.tooltip_mark").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.temporalindustries.portable_chrono_marker.tooltip_map").withStyle(ChatFormatting.GRAY));
    }
}
