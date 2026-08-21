package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.recipe.EntropyManipulatorRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.NotNull;

/**
 * JEI category for {@link EntropyManipulatorRecipe}: an input slot (item or, for fluid recipes,
 * the input fluid) feeding an arrow into the output item, plus an entropy slot below showing the
 * Order/Chaos fluid and amount the recipe consumes and the processing time as text.
 */
public class EntropyManipulatorRecipeCategory implements IRecipeCategory<RecipeHolder<EntropyManipulatorRecipe>> {
    private static final int WIDTH = 130;
    private static final int HEIGHT = 44;

    private static final int INPUT_X = 2;
    private static final int INPUT_Y = 6;
    private static final int ARROW_X = 25;
    private static final int ARROW_Y = 10;
    private static final int OUTPUT_X = 93;
    private static final int OUTPUT_Y = 6;
    private static final int ENTROPY_X = 2;
    private static final int ENTROPY_Y = 26;
    private static final int TIME_X = ENTROPY_X + 22;
    private static final int TIME_Y = ENTROPY_Y + 6;

    private final IDrawable icon;
    private final IDrawable arrow;

    public EntropyManipulatorRecipeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(Registration.ENTROPY_MANIPULATOR_ITEM.get().getDefaultInstance());
        this.arrow = guiHelper.getRecipeArrow();
    }

    @Override
    public RecipeType<RecipeHolder<EntropyManipulatorRecipe>> getRecipeType() {
        return TemporalIndustriesJeiPlugin.ENTROPY_MANIPULATOR_RECIPE_TYPE.get();
    }

    @Override
    public Component getTitle() {
        return Component.translatable("block.temporalindustries.entropy_manipulator");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<EntropyManipulatorRecipe> recipeHolder, IFocusGroup focuses) {
        EntropyManipulatorRecipe recipe = recipeHolder.value();

        IRecipeSlotBuilder inputSlot = builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + 1, INPUT_Y + 1)
                .setStandardSlotBackground();
        if (recipe.isFluidRecipe()) {
            recipe.inputFluidOpt().ifPresent(fluid -> inputSlot.addFluidStack(fluid, recipe.fluidAmount()));
        } else {
            recipe.inputItemOpt().ifPresent(inputSlot::addIngredients);
        }

        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                .setStandardSlotBackground()
                .addItemStack(recipe.result());

        Fluid entropyFluid = recipe.entropyType() == EntropyType.CHAOS
                ? Registration.CHAOS_FLUID.get()
                : Registration.ORDER_FLUID.get();
        builder.addSlot(RecipeIngredientRole.INPUT, ENTROPY_X + 1, ENTROPY_Y + 1)
                .setStandardSlotBackground()
                .addFluidStack(entropyFluid, recipe.entropyCost());
    }

    @Override
    public void draw(RecipeHolder<EntropyManipulatorRecipe> recipeHolder, @NotNull IRecipeSlotsView recipeSlotsView,
                      @NotNull GuiGraphics guiGraphics, double mouseX, double mouseY) {
        arrow.draw(guiGraphics, ARROW_X, ARROW_Y);

        EntropyManipulatorRecipe recipe = recipeHolder.value();
        Component time = Component.literal(String.format("%.1fs", recipe.processTicks() / 20f));
        guiGraphics.drawString(Minecraft.getInstance().font, time, TIME_X, TIME_Y, 0xFF808080, false);
    }
}
