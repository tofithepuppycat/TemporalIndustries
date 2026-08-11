package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/**
 * Datapack-driven damage type(s) this mod ships under {@code data/temporalindustries/damage_type/}
 * (and matching tag entries under {@code data/minecraft/tags/damage_type/}), mirroring how vanilla
 * exposes its own {@link net.minecraft.world.damagesource.DamageTypes} constants.
 */
public interface ModDamageTypes {
    /** Used by the Chrono Loop Projector to reapply a Chrono Recorder's recorded melee damage
     * (see {@link ChronoRecording.ActionType#ATTACK}). Tagged {@code bypasses_armor} because the
     * recorded amount is already the final, post-reduction damage from the original hit — applying
     * armor reduction again on replay would incorrectly halve it twice over. */
    ResourceKey<DamageType> CHRONO_ECHO = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_echo"));
}
