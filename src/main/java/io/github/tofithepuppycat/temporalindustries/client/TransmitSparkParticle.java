package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.ColorParticleOption;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** A short-lived, static spark used for the entropic pylon's transfer bolt. Unlike vanilla's
 * ENTITY_EFFECT, it has no gravity, physics, or inherited velocity, so a dense zigzag reads as a
 * static lightning bolt rather than a rising cloud. */
@OnlyIn(Dist.CLIENT)
public class TransmitSparkParticle extends TextureSheetParticle {
    private final SpriteSet sprites;

    private TransmitSparkParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D);
        this.sprites = sprites;
        this.gravity = 0.0F;
        this.hasPhysics = false;
        this.xd = 0.0D;
        this.yd = 0.0D;
        this.zd = 0.0D;
        this.quadSize *= 1.5F;
        this.lifetime = 3;
        this.setSpriteFromAge(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        this.setSpriteFromAge(this.sprites);
    }

    /** {@link #setAlpha} is protected on {@link Particle}; this wrapper lets {@link Provider} call it. */
    private void applyAlpha(float alpha) {
        this.setAlpha(alpha);
    }

    @OnlyIn(Dist.CLIENT)
    public static class Provider implements ParticleProvider<ColorParticleOption> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(ColorParticleOption type, ClientLevel level, double x, double y, double z,
                                        double xSpeed, double ySpeed, double zSpeed) {
            TransmitSparkParticle particle = new TransmitSparkParticle(level, x, y, z, sprites);
            particle.setColor(type.getRed(), type.getGreen(), type.getBlue());
            particle.applyAlpha(type.getAlpha());
            return particle;
        }
    }
}
