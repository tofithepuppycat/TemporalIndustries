package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A whole transmutation chain (e.g. stone -> cobblestone -> gravel -> sand -> redstone) built by
 * {@link TemporalIndustriesJeiPlugin} from every non-fluid {@code EntropyManipulatorRecipe},
 * grouping recipes that share an item into one path for display by
 * {@link EntropyManipulatorChainRecipeCategory}. {@code items.size() == steps.size() + 1}: each
 * step connects {@code items.get(i)} and {@code items.get(i + 1)}.
 */
public record EntropyManipulatorChain(List<ItemStack> items, List<Step> steps) {
    /** One link of the chain; either direction may be absent if that reverse recipe doesn't exist. */
    public record Step(@Nullable EntropyType forwardType, int forwardCost, @Nullable EntropyType backwardType, int backwardCost) {
    }
}
