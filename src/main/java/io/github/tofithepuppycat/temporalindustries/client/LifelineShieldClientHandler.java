package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.network.LifelineShieldPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client-side reaction to {@link LifelineShieldPacket}: an Order-coloured shockwave ring expanding
 * from the blast, and a spark that travels from the Lifeline to each block it saved.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class LifelineShieldClientHandler {
    private static final int SHOCKWAVE_TICKS = 14;
    private static final int BEAM_TRAVEL_TICKS = 10;
    /** Beams launch staggered over this many ticks so they read as a volley, not one flash. */
    private static final int BEAM_LAUNCH_SPREAD_TICKS = 5;
    private static final double BEAM_SPACING = 0.3;
    private static final double RING_POINT_SPACING = 0.3;
    /** Launch speed that makes a beam head drift roughly a block back toward the Lifeline. */
    private static final double SPARK_DRIFT_SPEED = 0.07;

    private static final Vector3f ORDER_COLOR = colorOf(EntropyType.ORDER);
    /**
     * Built on first use rather than in a static initializer: this class is loaded when the event
     * subscriber registers, which is before the particle type is bound.
     */
    @Nullable private static ColorParticleOption sparkOption;

    private static final List<Shield> ACTIVE = new ArrayList<>();

    private LifelineShieldClientHandler() {
    }

    /** The entropic pylon's transfer bolt spark, tinted Order. */
    private static ColorParticleOption spark() {
        if (sparkOption == null) {
            sparkOption = ColorParticleOption.create(
                    Registration.TRANSMIT_SPARK.get(), ORDER_COLOR.x, ORDER_COLOR.y, ORDER_COLOR.z);
        }
        return sparkOption;
    }

    private static final class BeamState {
        final Vec3 from;
        final Vec3 to;
        final int launchTick;

        BeamState(Vec3 from, Vec3 to, int launchTick) {
            this.from = from;
            this.to = to;
            this.launchTick = launchTick;
        }
    }

    private static final class Shield {
        final ClientLevel level;
        final Vec3 center;
        final double maxRadius;
        final List<BeamState> beams = new ArrayList<>();
        int age;

        Shield(ClientLevel level, Vec3 center, double maxRadius) {
            this.level = level;
            this.center = center;
            this.maxRadius = maxRadius;
        }

        boolean finished() {
            return age > SHOCKWAVE_TICKS && age > BEAM_LAUNCH_SPREAD_TICKS + BEAM_TRAVEL_TICKS;
        }
    }

    public static void onShield(Vec3 center, float radius, List<LifelineShieldPacket.Beam> beams) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        Shield shield = new Shield(level, center, Math.max(2.0, radius * 1.6));
        for (LifelineShieldPacket.Beam beam : beams) {
            // The server already skips air, but the client may know better (e.g. a block it just
            // saw break), and a beam ending in empty space looks wrong.
            if (level.getBlockState(beam.target()).isAir()) {
                continue;
            }
            shield.beams.add(new BeamState(Vec3.atCenterOf(beam.lifeline()), Vec3.atCenterOf(beam.target()),
                    level.random.nextInt(BEAM_LAUNCH_SPREAD_TICKS + 1)));
        }
        ACTIVE.add(shield);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        ClientLevel current = Minecraft.getInstance().level;

        Iterator<Shield> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Shield shield = it.next();
            if (shield.level != current || shield.finished()) {
                it.remove();
                continue;
            }
            tickShockwave(shield);
            tickBeams(shield);
            shield.age++;
        }
    }

    /** Three orthogonal rings that grow together and fade out, so the front reads as a sphere. */
    private static void tickShockwave(Shield shield) {
        if (shield.age > SHOCKWAVE_TICKS) {
            return;
        }
        double t = shield.age / (double) SHOCKWAVE_TICKS;
        // Ease-out: fast at first, settling as it reaches the edge of the blast.
        double r = shield.maxRadius * (1.0 - (1.0 - t) * (1.0 - t));
        int points = Math.max(12, (int) (2.0 * Math.PI * r / RING_POINT_SPACING));
        Vec3 c = shield.center;

        for (int i = 0; i < points; i++) {
            double a = i * (2.0 * Math.PI / points);
            double cos = Math.cos(a) * r;
            double sin = Math.sin(a) * r;
            shield.level.addParticle(spark(), c.x + cos, c.y, c.z + sin, 0.0, 0.0, 0.0);
            if (i % 3 == 0) {
                shield.level.addParticle(ParticleTypes.END_ROD, c.x + cos, c.y, c.z + sin, 0.0, 0.0, 0.0);
            }
            if (i % 2 == 0) {
                shield.level.addParticle(spark(), c.x + cos, c.y + sin, c.z, 0.0, 0.0, 0.0);
                shield.level.addParticle(spark(), c.x, c.y + cos, c.z + sin, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * Each beam is a bright head racing from the Lifeline to its block, leaving a trail of dust
     * behind it, with a flash where it lands.
     */
    private static void tickBeams(Shield shield) {
        for (BeamState beam : shield.beams) {
            int step = shield.age - beam.launchTick;
            if (step < 0 || step > BEAM_TRAVEL_TICKS) {
                continue;
            }
            double t0 = Math.max(0, step - 1) / (double) BEAM_TRAVEL_TICKS;
            double t1 = step / (double) BEAM_TRAVEL_TICKS;
            Vec3 delta = beam.to.subtract(beam.from);
            // Only the head's end rod drifts back toward the Lifeline; the trail sparks are static.
            Vec3 sparkDrift = delta.normalize().scale(-SPARK_DRIFT_SPEED);
            int samples = Math.max(1, (int) (delta.length() * (t1 - t0) / BEAM_SPACING));

            for (int i = 0; i <= samples; i++) {
                double t = t0 + (t1 - t0) * (i / (double) samples);
                Vec3 p = beam.from.add(delta.scale(t));
                shield.level.addParticle(spark(), p.x, p.y, p.z, 0.0, 0.0, 0.0);
            }
            Vec3 head = beam.from.add(delta.scale(t1));
            shield.level.addParticle(ParticleTypes.END_ROD, head.x, head.y, head.z, sparkDrift.x, sparkDrift.y, sparkDrift.z);

            if (step == BEAM_TRAVEL_TICKS) {
                shield.level.addParticle(ParticleTypes.FLASH, beam.to.x, beam.to.y, beam.to.z, 0.0, 0.0, 0.0);
                for (int i = 0; i < 4; i++) {
                    shield.level.addParticle(ParticleTypes.END_ROD, beam.to.x, beam.to.y, beam.to.z,
                            shield.level.random.nextGaussian() * 0.04,
                            shield.level.random.nextGaussian() * 0.04,
                            shield.level.random.nextGaussian() * 0.04);
                }
            }
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
