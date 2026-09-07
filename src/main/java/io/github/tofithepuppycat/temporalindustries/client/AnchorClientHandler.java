package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/** Client-side reaction to {@link io.github.tofithepuppycat.temporalindustries.network.AnchorStatusPacket}
 * and {@link io.github.tofithepuppycat.temporalindustries.network.AnchorRewindEffectPacket}, kept
 * separate so the packet classes don't need to reference client-only types. */
public final class AnchorClientHandler {
    private static final Vector3f REWIND_PARTICLE_COLOR = new Vector3f(
            ((EntropyType.CHAOS.color() >> 16) & 0xFF) / 255.0F,
            ((EntropyType.CHAOS.color() >> 8) & 0xFF) / 255.0F,
            (EntropyType.CHAOS.color() & 0xFF) / 255.0F);

    private AnchorClientHandler() {
    }

    public static void onReverted(int revertedChangeCount, int foodLevel, float saturation, float exhaustion) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        player.getFoodData().setFoodLevel(foodLevel);
        player.getFoodData().setSaturation(saturation);
        player.getFoodData().setExhaustion(exhaustion);

        minecraft.gui.setTimes(10, 70, 20);
        minecraft.gui.setTitle(Component.translatable("temporalindustries.anchor.reverted.title"));
        minecraft.gui.setSubtitle(Component.translatable("temporalindustries.anchor.reverted.subtitle", revertedChangeCount));
    }

    public static void onRewindEffect(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        Entity entity = level.getEntity(entityId);
        if (!(entity instanceof LivingEntity livingEntity)) {
            return;
        }

        DustParticleOptions particle = new DustParticleOptions(REWIND_PARTICLE_COLOR, 1.3F);
        for (int i = 0; i < 30; i++) {
            double xd = livingEntity.getRandom().nextGaussian() * 0.05;
            double yd = livingEntity.getRandom().nextGaussian() * 0.05;
            double zd = livingEntity.getRandom().nextGaussian() * 0.05;
            double x = livingEntity.getRandomX(1.0);
            double y = livingEntity.getRandomY();
            double z = livingEntity.getRandomZ(1.0);
            level.addParticle(particle, x, y, z, xd, yd, zd);
        }

        level.playLocalSound(livingEntity.getX(), livingEntity.getY(), livingEntity.getZ(),
                SoundEvents.TOTEM_USE, livingEntity.getSoundSource(), 1.0F, 1.0F, false);

        if (livingEntity == minecraft.player) {
            minecraft.gameRenderer.displayItemActivation(new ItemStack(Registration.TEMPORAL_ANCHOR_ITEM.get()));
        }
    }
}
