package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.world.item.ItemStack;

/**
 * Implemented by items that store liquid Order/Chaos on the stack and can catch an
 * {@link EntropyOrbEntity} out of the world. All amounts are millibuckets of the matching fluid.
 */
public interface EntropyReceptacle {
    /** Whether a stack of this item will attract/accept liquid of the given type. */
    boolean accepts(EntropyType type);

    /** Per-type tank size in mB. */
    int capacity(EntropyType type);

    /** How many mB of the given type the stack currently holds. */
    int amount(ItemStack stack, EntropyType type);

    /** Adds up to millibuckets of type into stack, respecting capacity. Returns how much went in. */
    int fill(ItemStack stack, EntropyType type, int millibuckets);

    /** Pulls up to millibuckets of type out of stack. Returns how much came out. */
    int drain(ItemStack stack, EntropyType type, int millibuckets);

    /** Whether stack currently has spare capacity for the given type. */
    default boolean hasRoom(ItemStack stack, EntropyType type) {
        return accepts(type) && amount(stack, type) < capacity(type);
    }
}
