package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.screen.EntropyManipulatorScreen;
import io.github.tofithepuppycat.temporalindustries.recipe.EntropyManipulatorRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;

/**
 * Discovered by JEI via the {@link JeiPlugin} annotation (NeoForge scans annotated classes at
 * mod-load time; nothing here runs, or even loads, when JEI isn't installed). Adds a "Show
 * recipes" click area over the Entropy Manipulator's progress bar (see
 * {@link EntropyManipulatorScreen#PROGRESS_X}) the same way Mekanism's machines do, so hovering
 * it shows the prompt and clicking opens the recipe category built by
 * {@link EntropyManipulatorRecipeCategory}.
 */
@JeiPlugin
public class TemporalIndustriesJeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_UID = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "jei_plugin");

    public static final RecipeType<RecipeHolder<EntropyManipulatorRecipe>> ENTROPY_MANIPULATOR_RECIPE_TYPE =
            RecipeType.createFromVanilla(Registration.ENTROPY_MANIPULATOR_RECIPE_TYPE.get());

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new EntropyManipulatorRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;

        List<RecipeHolder<EntropyManipulatorRecipe>> recipes =
                level.getRecipeManager().getAllRecipesFor(Registration.ENTROPY_MANIPULATOR_RECIPE_TYPE.get());
        registration.addRecipes(ENTROPY_MANIPULATOR_RECIPE_TYPE, recipes);
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(Registration.ENTROPY_MANIPULATOR_ITEM.get().getDefaultInstance(), ENTROPY_MANIPULATOR_RECIPE_TYPE);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addRecipeClickArea(EntropyManipulatorScreen.class,
                EntropyManipulatorScreen.PROGRESS_X, EntropyManipulatorScreen.PROGRESS_Y,
                EntropyManipulatorScreen.PROGRESS_WIDTH, EntropyManipulatorScreen.PROGRESS_HEIGHT,
                ENTROPY_MANIPULATOR_RECIPE_TYPE);
    }
}
