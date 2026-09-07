package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/**
 * Datapack-driven damage type(s) this mod ships under {@code data/temporalindustries/damage_type/}.
 */
public interface ModDamageTypes {
    /** Used by the Chrono Loop Projector to reapply a Chrono Record's recorded melee damage.
     * Tagged {@code bypasses_armor} since the recorded amount is already post-reduction damage. */
    ResourceKey<DamageType> CHRONO_ECHO = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_echo"));
}
