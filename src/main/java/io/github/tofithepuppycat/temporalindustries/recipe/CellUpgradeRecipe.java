package io.github.tofithepuppycat.temporalindustries.recipe;

import com.mojang.serialization.MapCodec;
import io.github.tofithepuppycat.temporalindustries.Registration;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Shapeless recipe that carries the stored Order/Chaos of the cell being upgraded over to the
 * resulting cell.
 */
@SuppressWarnings("null")
public class CellUpgradeRecipe extends ShapelessRecipe {
    public CellUpgradeRecipe(String group, CraftingBookCategory category, ItemStack result, NonNullList<Ingredient> ingredients) {
        super(group, category, result, ingredients);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = super.assemble(input, registries);
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.has(Registration.BOTTLE_CONTENTS.get())) {
                result.set(Registration.BOTTLE_CONTENTS.get(), stack.get(Registration.BOTTLE_CONTENTS.get()));
            }
            if (stack.has(Registration.ENTROPY_CONTENTS.get())) {
                result.set(Registration.ENTROPY_CONTENTS.get(), stack.get(Registration.ENTROPY_CONTENTS.get()));
            }
        }
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return Registration.CELL_UPGRADE_RECIPE_SERIALIZER.get();
    }

    private static CellUpgradeRecipe from(ShapelessRecipe recipe) {
        return new CellUpgradeRecipe(recipe.getGroup(), recipe.category(), recipe.getResultItem(RegistryAccess.EMPTY), recipe.getIngredients());
    }

    /** Reads and writes the same format as a vanilla shapeless recipe. */
    public static class Serializer implements RecipeSerializer<CellUpgradeRecipe> {
        private final ShapelessRecipe.Serializer delegate = new ShapelessRecipe.Serializer();
        private final MapCodec<CellUpgradeRecipe> codec = delegate.codec().xmap(CellUpgradeRecipe::from, recipe -> recipe);
        private final StreamCodec<RegistryFriendlyByteBuf, CellUpgradeRecipe> streamCodec = delegate.streamCodec().map(CellUpgradeRecipe::from, recipe -> recipe);

        @Override
        public MapCodec<CellUpgradeRecipe> codec() {
            return codec;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CellUpgradeRecipe> streamCodec() {
            return streamCodec;
        }
    }
}
