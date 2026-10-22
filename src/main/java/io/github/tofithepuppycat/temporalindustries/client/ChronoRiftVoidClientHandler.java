package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftDimension;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * Client half of flying through untracked Chrono Rift chunks: fills them with chaos-colored
 * particles and a trail of order-colored particles behind the player.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class ChronoRiftVoidClientHandler {
    private static final Vector3f CHAOS_COLOR = new Vector3f(
            ((EntropyType.CHAOS.color() >> 16) & 0xFF) / 255.0F,
            ((EntropyType.CHAOS.color() >> 8) & 0xFF) / 255.0F,
            (EntropyType.CHAOS.color() & 0xFF) / 255.0F);
    private static final DustParticleOptions CHAOS_PARTICLE = new DustParticleOptions(CHAOS_COLOR, 1.0F);
    private static final DustParticleOptions ORDER_PARTICLE = new DustParticleOptions(new Vector3f(
            ((EntropyType.ORDER.color() >> 16) & 0xFF) / 255.0F,
            ((EntropyType.ORDER.color() >> 8) & 0xFF) / 255.0F,
            (EntropyType.ORDER.color() & 0xFF) / 255.0F), 1.0F);

    private static final int TRAIL_PARTICLES_PER_TICK = 3;
    private static final double TRAIL_MIN_SPEED_SQR = 1.0E-4D;

    private static final int PARTICLE_ATTEMPTS_PER_TICK = 24;
    private static final int PARTICLE_RADIUS = 14;
    private static final int PARTICLE_HEIGHT = 7;

    private ChronoRiftVoidClientHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || level.dimension() != ChronoRiftDimension.LEVEL_KEY
                || ChronoRiftClientState.isTracked(new ChunkPos(player.blockPosition()))) {
            return;
        }

        spawnChaosParticles(level, player.position());
        spawnOrderTrail(level, player);
    }

    private static void spawnOrderTrail(ClientLevel level, LocalPlayer player) {
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        if (dx * dx + dz * dz < TRAIL_MIN_SPEED_SQR) return;

        RandomSource random = level.getRandom();
        for (int i = 0; i < TRAIL_PARTICLES_PER_TICK; i++) {
            level.addParticle(ORDER_PARTICLE,
                    player.getX() + (random.nextDouble() - 0.5D) * 0.6D,
                    player.getY() + 0.1D + random.nextDouble() * 0.1D,
                    player.getZ() + (random.nextDouble() - 0.5D) * 0.6D,
                    0.0D, 0.01D, 0.0D);
        }
    }

    private static void spawnChaosParticles(ClientLevel level, Vec3 center) {
        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < PARTICLE_ATTEMPTS_PER_TICK; i++) {
            double x = center.x + (random.nextDouble() * 2.0D - 1.0D) * PARTICLE_RADIUS;
            double y = center.y + (random.nextDouble() * 2.0D - 1.0D) * PARTICLE_HEIGHT;
            double z = center.z + (random.nextDouble() * 2.0D - 1.0D) * PARTICLE_RADIUS;
            pos.set(x, y, z);

            ChunkPos chunk = new ChunkPos(pos);
            if (ChronoRiftClientState.isTracked(chunk) || !level.hasChunk(chunk.x, chunk.z)) continue;

            level.addParticle(CHAOS_PARTICLE, x, y, z,
                    random.nextGaussian() * 0.01D, random.nextGaussian() * 0.01D, random.nextGaussian() * 0.01D);
        }
    }
}
