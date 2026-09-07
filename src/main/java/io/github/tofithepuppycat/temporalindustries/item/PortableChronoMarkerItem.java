package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChunkArea;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.ChunkSnapshot;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handheld player save-point tool. Right-click captures a fresh {@link ChunkSnapshot} of every chunk
 * in the marker's shape and diffs it against the chunk's timeline head, committing a DELTA (or a
 * fresh SNAPSHOT once drifted far enough) — the same commit types auto-tracking produces, so the
 * save point is restorable like any other. Unlike a placed machine, it never registers continuous
 * background tracking; it only touches the timeline at the moment of a save. The resulting commit is
 * flagged player-marked so the graph can highlight it.
 *
 * <p>A plain right-click marks a shape around the player — the default fixed square, or the
 * player's saved custom selection (see {@link #getSavedOffsets}) re-centred on their position.
 * Sneak + right-click opens {@link io.github.tofithepuppycat.temporalindustries.client.screen.ChronoMarkerMapScreen}
 * to pick a custom shape; the map screen only saves the selection, it never marks by itself.
 */
@SuppressWarnings("null")
public class PortableChronoMarkerItem extends Item {
    private static final int RADIUS_CHUNKS = 2;
    /** Radius (in chunks) offered by the sneak-right-click area-select map; matches the Chronosphere's claim radius. */
    public static final int MAP_RADIUS_CHUNKS = ChronosphereBlockEntity.MAX_RADIUS;
    /** The area-select map's claimable outline (square, matching the Chronosphere's claim shape). */
    public static final ChunkArea.Shape MAP_SHAPE = ChunkArea.Shape.SQUARE;
    private static final int WAVE_DURATION_TICKS = 15;
    private static final double RING_SPEED = 6.0D;

    /** NBT key for the player's saved custom shape: flat [dx0, dz0, ...] chunk offsets relative to
     * where the marker is used. Absent/empty falls back to the fixed {@link #RADIUS_CHUNKS} square. */
    private static final String TAG_SHAPE_OFFSETS = "ShapeOffsets";

    /** One chunk offset in a saved custom shape, relative to where the marker is used. */
    public record ChunkOffset(int dx, int dz) {}

    /** playerId -> gameTime the current terrain-tracing wave started. Cosmetic only, never persisted. */
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

        ChunkPos center = new ChunkPos(player.blockPosition());
        recordMark(serverPlayer, (ServerLevel) level, chunksForMark(stack, center));
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!isSelected || !(level instanceof ServerLevel serverLevel) || !(entity instanceof ServerPlayer player)) return;

        int visualRadius = visualRadiusChunks(stack);
        spawnBoundaryWall(player, serverLevel, visualRadius);
        spawnWave(player, serverLevel, visualRadius);
    }

    /** Records a save point across {@code chunks} from two point-in-time captures (no continuous
     * tracking). Ensures each chunk has a baseline, then diffs it into a DELTA or, once drifted far
     * enough, a fresh SNAPSHOT — matching what auto-tracking would produce. */
    public static void recordMark(ServerPlayer player, ServerLevel level, List<ChunkPos> chunks) {
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        TemporalTimeline timeline = worldData.getOrCreateTimeline(dimension);

        List<ChunkDelta> chunkDeltas = new ArrayList<>();
        List<ChunkSnapshot> chunkSnapshots = new ArrayList<>();

        for (ChunkPos chunkPos : chunks) {
            // First-time mark: baseline captured here, nothing to diff yet.
            if (timeline.ensureBaseline(chunkPos, level)) continue;

            ChunkSnapshot current = ChunkSnapshot.capture(level, chunkPos);
            if (timeline.getCommitsSinceSnapshot(chunkPos) >= TemporalTimeline.SNAPSHOT_COMMIT_THRESHOLD) {
                chunkSnapshots.add(current);
                continue;
            }

            ChunkDelta delta = timeline.diffChunkAgainstHead(dimension, current);
            if (delta != null) chunkDeltas.add(delta);
        }

        // Flagged player-marked so the graph renders these with the special mark icon.
        if (!chunkDeltas.isEmpty()) timeline.addDelta(level.getGameTime(), chunkDeltas, true);
        if (!chunkSnapshots.isEmpty()) timeline.addSnapshot(level.getGameTime(), chunkSnapshots, true);
        worldData.setDirty();

        player.displayClientMessage(Component.translatable("item.temporalindustries.portable_chrono_marker.marked"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.PLAYERS, 1.0F, 1.2F);
        level.sendParticles(ParticleTypes.SONIC_BOOM, player.getX(), player.getY() + 1.0D, player.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        ACTIVE_WAVE_START.put(player.getUUID(), level.getGameTime());
    }

    private static List<ChunkPos> chunksInRadius(ChunkPos center) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                chunks.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        return chunks;
    }

    /** Every chunk a plain right-click with {@code stack} would mark, centred on {@code center}. */
    public static List<ChunkPos> chunksForMark(ItemStack stack, ChunkPos center) {
        List<ChunkOffset> offsets = getSavedOffsets(stack);
        if (offsets.isEmpty()) return chunksInRadius(center);

        List<ChunkPos> chunks = new ArrayList<>(offsets.size());
        for (ChunkOffset offset : offsets) {
            chunks.add(new ChunkPos(center.x + offset.dx(), center.z + offset.dz()));
        }
        return chunks;
    }

    /** The player's saved custom shape, or empty if none has been saved yet. */
    public static List<ChunkOffset> getSavedOffsets(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!data.contains(TAG_SHAPE_OFFSETS)) return List.of();

        int[] flat = data.getIntArray(TAG_SHAPE_OFFSETS);
        List<ChunkOffset> offsets = new ArrayList<>(flat.length / 2);
        for (int i = 0; i + 1 < flat.length; i += 2) {
            offsets.add(new ChunkOffset(flat[i], flat[i + 1]));
        }
        return offsets;
    }

    /** Persists {@code offsets} onto {@code stack} as its custom shape, replacing any previous selection. */
    public static void saveOffsets(ItemStack stack, List<ChunkOffset> offsets) {
        int[] flat = new int[offsets.size() * 2];
        for (int i = 0; i < offsets.size(); i++) {
            flat[i * 2] = offsets.get(i).dx();
            flat[i * 2 + 1] = offsets.get(i).dz();
        }

        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putIntArray(TAG_SHAPE_OFFSETS, flat);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    /** Half-width (in chunks) of the boundary visuals. */
    private static int visualRadiusChunks(ItemStack stack) {
        List<ChunkOffset> offsets = getSavedOffsets(stack);
        if (offsets.isEmpty()) return RADIUS_CHUNKS;

        int radius = 0;
        for (ChunkOffset offset : offsets) {
            radius = Math.max(radius, Math.max(Math.abs(offset.dx()), Math.abs(offset.dz())));
        }
        return radius;
    }

    /** Sparse particle grid along the edges of the capture square, so the player can see where
     * tracking currently ends. */
    private static void spawnBoundaryWall(ServerPlayer player, ServerLevel level, int radiusChunks) {
        if (level.getGameTime() % 4 != 0) return;

        int radiusBlocks = radiusChunks * 16;
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
            level.sendParticles(ParticleTypes.SCULK_SOUL, x + 0.5D, centerY + dy, z + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** Expanding ring of particles tracing the terrain outward from the player after a mark, until it reaches the capture radius. */
    private static void spawnWave(ServerPlayer player, ServerLevel level, int radiusChunks) {
        Long start = ACTIVE_WAVE_START.get(player.getUUID());
        if (start == null) return;

        long elapsed = level.getGameTime() - start;
        double radius = elapsed * RING_SPEED;
        double maxRadius = radiusChunks * 16.0D;
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
            level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, x + 0.5D, y + 0.2D, z + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        TooltipUtil.appendDescription(tooltip,
                "item.temporalindustries.portable_chrono_marker.tooltip",
                "item.temporalindustries.portable_chrono_marker.tooltip_mark",
                "item.temporalindustries.portable_chrono_marker.tooltip_map");
    }
}
