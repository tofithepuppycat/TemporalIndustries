package io.github.tofithepuppycat.temporalindustries.dimension;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;

/**
 * Chronodial, Chronovault and Chronosphere all refuse to operate while inside the Chrono Rift
 * itself - letting a timeline machine run inside the rift it's built out of would let a player
 * rewrite the history the rift is replaying.
 */
public final class RiftDeviceLock {
    private static final ResourceLocation PARADOX_AVERTED_ID =
            ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "paradox_averted");

    private RiftDeviceLock() {}

    /** Call from a block's use handler once its {@code BlockEntity} lookup has succeeded. */
    public static boolean blockIfInRift(Level level, Player player) {
        if (level.dimension() != ChronoRiftDimension.LEVEL_KEY) {
            return false;
        }

        player.displayClientMessage(Component.translatable("temporalindustries.rift_device_lock.blocked"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (player instanceof ServerPlayer serverPlayer) {
            MinecraftServer server = serverPlayer.getServer();
            AdvancementHolder advancement = server != null ? server.getAdvancements().get(PARADOX_AVERTED_ID) : null;
            if (advancement != null) {
                serverPlayer.getAdvancements().award(advancement, "attempted_use");
            }
        }
        return true;
    }
}
