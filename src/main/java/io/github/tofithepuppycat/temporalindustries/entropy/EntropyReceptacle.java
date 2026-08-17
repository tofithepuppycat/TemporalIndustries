package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.world.item.ItemStack;

/**
 * Implemented by items that can catch an {@link EntropyOrbEntity} out of the world — the
 * {@link io.github.tofithepuppycat.temporalindustries.item.EntropyContainerItem} (both types) and
 * the single-type Order/Chaos bottles ({@link io.github.tofithepuppycat.temporalindustries.item.EntropyBottleItem}).
 */
public interface EntropyReceptacle {
    /** Whether a stack of this item will attract/accept an orb of the given type. */
    boolean accepts(EntropyType type);

    /** Adds amount of type into stack, respecting capacity. Returns whatever didn't fit. */
    int insertOrb(ItemStack stack, EntropyType type, int amount);
}
