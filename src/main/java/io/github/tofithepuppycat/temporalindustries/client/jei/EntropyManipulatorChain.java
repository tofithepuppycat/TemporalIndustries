package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A transmutation chain (e.g. stone -> cobblestone -> gravel -> sand -> redstone) built from
 * connected {@code EntropyManipulatorRecipe}s for display by {@link EntropyManipulatorChainRecipeCategory}.
 * {@code items.size() == steps.size() + 1}: each step connects {@code items.get(i)} and {@code items.get(i + 1)}.
 */
public record EntropyManipulatorChain(List<Node> items, List<Step> steps) {
    /**
     * One item slot in the chain. {@code options} is the set of stacks the producing recipe could
     * yield: a single correctly-counted stack for a fixed result, or every item in the tag (each
     * with {@code result_count} applied) for a tag-based result, cycled by JEI like any other slot.
     */
    public record Node(List<ItemStack> options) {
    }

    /** One link of the chain; either direction may be absent if that reverse recipe doesn't exist. */
    public record Step(@Nullable EntropyType forwardType, int forwardCost, @Nullable EntropyType backwardType, int backwardCost) {
    }
}
