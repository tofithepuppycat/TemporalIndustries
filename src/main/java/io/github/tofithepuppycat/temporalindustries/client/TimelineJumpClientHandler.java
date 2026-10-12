package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.joml.Vector3f;

import java.util.List;

/**
 * Client-side reaction to {@link
 * io.github.tofithepuppycat.temporalindustries.network.TimelineJumpEffectPacket}: a swirling
 * particle burst around a timeline machine the instant it lands on a different commit, kept
 * separate from the packet class so that class doesn't need to reference client-only types.
 */
public final class TimelineJumpClientHandler {
    private static final Vector3f FUTURE_COLOR = colorOf(EntropyType.CHAOS);
    private static final Vector3f PAST_COLOR = colorOf(EntropyType.ORDER);

    private TimelineJumpClientHandler() {
    }

    public static void onJumpEffect(BlockPos pos, boolean intoFuture) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        Vector3f color = intoFuture ? FUTURE_COLOR : PAST_COLOR;
        DustParticleOptions dust = new DustParticleOptions(color, 1.2F);
        float spin = intoFuture ? 1.0F : -1.0F;

        // Rising double helix: the "temporal corridor" opening up around the machine.
        int helixSteps = 48;
        for (int i = 0; i < helixSteps; i++) {
            double t = i / (double) helixSteps;
            double angle = spin * t * Math.PI * 6.0;
            double radius = 0.9 - t * 0.6;
            double height = t * 2.2;

            double x1 = cx + Math.cos(angle) * radius;
            double z1 = cz + Math.sin(angle) * radius;
            level.addParticle(dust, x1, cy + height, z1, 0.0, 0.015, 0.0);

            double x2 = cx + Math.cos(angle + Math.PI) * radius;
            double z2 = cz + Math.sin(angle + Math.PI) * radius;
            level.addParticle(ParticleTypes.END_ROD, x2, cy + height, z2, 0.0, 0.01, 0.0);
        }

        // Expanding shockwave ring at the base.
        int ringSteps = 28;
        for (int i = 0; i < ringSteps; i++) {
            double angle = (i / (double) ringSteps) * Math.PI * 2.0;
            double x = cx + Math.cos(angle) * 1.1;
            double z = cz + Math.sin(angle) * 1.1;
            level.addParticle(dust, x, cy - 0.3, z,
                    Math.cos(angle) * 0.04, 0.02, Math.sin(angle) * 0.04);
        }

        // A brief sparkle burst to sell the "fancy" of it.
        for (int i = 0; i < 16; i++) {
            double xd = level.random.nextGaussian() * 0.08;
            double yd = level.random.nextGaussian() * 0.08;
            double zd = level.random.nextGaussian() * 0.08;
            level.addParticle(ParticleTypes.REVERSE_PORTAL, cx, cy + 0.5, cz, xd, yd, zd);
        }
    }

    /**
     * Flashes every block a jump just rewrote: a brief shell of colored sparks hugging each
     * position, so the rewrite reads as a wave settling across the claimed area instead of popping
     * in silently.
     */
    public static void onBlockChangeFlash(List<BlockPos> positions, boolean intoFuture) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        Vector3f color = intoFuture ? FUTURE_COLOR : PAST_COLOR;
        DustParticleOptions dust = new DustParticleOptions(color, 1.0F);

        for (BlockPos pos : positions) {
            double cx = pos.getX() + 0.5;
            double cy = pos.getY() + 0.5;
            double cz = pos.getZ() + 0.5;

            for (int i = 0; i < 3; i++) {
                double ox = (level.random.nextDouble() - 0.5) * 0.9;
                double oy = (level.random.nextDouble() - 0.5) * 0.9;
                double oz = (level.random.nextDouble() - 0.5) * 0.9;
                level.addParticle(dust, cx + ox, cy + oy, cz + oz, 0.0, 0.01, 0.0);
            }
            level.addParticle(ParticleTypes.END_ROD, cx, cy, cz, 0.0, 0.03, 0.0);
        }
    }

    private static Vector3f colorOf(EntropyType type) {
        int color = type.color();
        return new Vector3f(
                ((color >> 16) & 0xFF) / 255.0F,
                ((color >> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F);
    }
}
