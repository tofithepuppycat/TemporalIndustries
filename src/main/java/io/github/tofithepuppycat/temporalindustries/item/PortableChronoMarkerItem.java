package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.network.ChronoMarkerDiffSyncPacket;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handheld player bookmark tool. While held, tracks (and shows the boundary of) a chunk radius
 * around the wielder — the same {@link TemporalWorldData#trackChunk} mechanism placed machines use
 * — so the timeline keeps recording deltas there. Crouch right-click records a zero-diff
 * {@link TemporalCommit.Type#PLAYER_MARK} waypoint across that radius (see
 * {@link TemporalTimeline#addPlayerMark}); a plain right-click asks the server for the block
 * changes between the wielder's latest two marks and opens a screen listing them.
 */
@SuppressWarnings("null")
public class PortableChronoMarkerItem extends Item {
    private static final int RADIUS_CHUNKS = 2;
    private static final int TRACK_INTERVAL_TICKS = 20;
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
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }

        if (player.isShiftKeyDown()) {
            recordMark(serverPlayer, (ServerLevel) level);
        } else {
            sendDiff(serverPlayer, (ServerLevel) level);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!isSelected || !(level instanceof ServerLevel serverLevel) || !(entity instanceof ServerPlayer player)) return;

        if (serverLevel.getGameTime() % TRACK_INTERVAL_TICKS == 0) {
            trackRadius(player, serverLevel);
        }
        spawnBoundaryWall(player, serverLevel);
        spawnWave(player, serverLevel);
    }

    // -------------------------------------------------------------------------
    // Marking

    private void recordMark(ServerPlayer player, ServerLevel level) {
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        TemporalTimeline timeline = worldData.getOrCreateTimeline(level.dimension().location());

        List<ChunkPos> chunks = chunksInRadius(player);
        for (ChunkPos chunkPos : chunks) {
            worldData.trackChunk(chunkPos);
            timeline.ensureBaseline(chunkPos, level);
        }

        timeline.addPlayerMark(level.getGameTime(), chunks, player.getUUID());
        worldData.setDirty();

        player.displayClientMessage(Component.translatable("item.temporalindustries.portable_chrono_marker.marked"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.2F);
        ACTIVE_WAVE_START.put(player.getUUID(), level.getGameTime());
    }

    private void sendDiff(ServerPlayer player, ServerLevel level) {
        MinecraftServer server = level.getServer();
        TemporalWorldData worldData = TemporalWorldData.get(server);
        TemporalTimeline timeline = worldData.getTimeline(level.dimension().location());

        List<TemporalCommit> marks = timeline == null ? List.of() : timeline.getLatestPlayerMarks(player.getUUID(), 2);
        if (marks.size() < 2) {
            player.displayClientMessage(Component.translatable("item.temporalindustries.portable_chrono_marker.no_marks"), true);
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0F, 1.0F);
            return;
        }

        TemporalCommit older = marks.get(0);
        TemporalCommit newer = marks.get(1);

        List<ChronoMarkerDiffSyncPacket.Entry> entries = new ArrayList<>();
        for (ChunkPos chunkPos : newer.getMarkedChunks()) {
            for (TemporalTimeline.BlockDiffEntry diff : timeline.diffChunkBetweenMarks(chunkPos, older, newer)) {
                entries.add(new ChronoMarkerDiffSyncPacket.Entry(diff.pos(), diff.oldState(), diff.newState()));
            }
        }

        PacketDistributor.sendToPlayer(player, new ChronoMarkerDiffSyncPacket(older.getGameTime(), newer.getGameTime(), entries));
    }

    // -------------------------------------------------------------------------
    // Radius / tracking

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

    private static void trackRadius(ServerPlayer player, ServerLevel level) {
        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        TemporalTimeline timeline = worldData.getOrCreateTimeline(level.dimension().location());
        for (ChunkPos chunkPos : chunksInRadius(player)) {
            worldData.trackChunk(chunkPos);
            timeline.ensureBaseline(chunkPos, level);
        }
        worldData.setDirty();
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
    }
}
