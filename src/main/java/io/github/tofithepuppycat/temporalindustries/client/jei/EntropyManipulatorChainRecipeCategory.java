package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.Registration;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
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
import org.jetbrains.annotations.NotNull;

/**
 * JEI category for {@link EntropyManipulatorChain}: a whole transmutation chain laid out as a
 * horizontal row of item slots, with the chaos/order recipe cost connecting each adjacent pair
 * drawn as colored text above (forward) and below (backward) the gap between them. Supplements
 * {@link EntropyManipulatorRecipeCategory}, which still handles single-step "how do I make/use
 * this item" lookups; this category exists purely to visualize the full path at once.
 */
public class EntropyManipulatorChainRecipeCategory implements IRecipeCategory<EntropyManipulatorChain> {
    /** Longest chain currently defined (netherrack->magma_block->obsidian->crying_obsidian->nether_star, etc). */
    private static final int MAX_ITEMS = 5;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_GAP = 4;
    private static final int STEP_WIDTH = 50;
    private static final int STRIDE = SLOT_SIZE + SLOT_GAP + STEP_WIDTH;

    private static final int WIDTH = MAX_ITEMS * (SLOT_SIZE + SLOT_GAP) + (MAX_ITEMS - 1) * STEP_WIDTH;
    private static final int HEIGHT = 54;
    private static final int ITEM_Y = 18;

    private final IDrawable icon;

    public EntropyManipulatorChainRecipeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(Registration.ENTROPY_MANIPULATOR_ITEM.get().getDefaultInstance());
    }

    @Override
    public RecipeType<EntropyManipulatorChain> getRecipeType() {
        return TemporalIndustriesJeiPlugin.ENTROPY_MANIPULATOR_CHAIN_RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.temporalindustries.entropy_manipulator_chain");
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
    public void setRecipe(IRecipeLayoutBuilder builder, EntropyManipulatorChain chain, IFocusGroup focuses) {
        int last = chain.items().size() - 1;
        for (int i = 0; i <= last; i++) {
            RecipeIngredientRole role = i == last ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT;
            builder.addSlot(role, i * STRIDE + 1, ITEM_Y + 1)
                    .setStandardSlotBackground()
                    .addItemStack(chain.items().get(i));
        }
    }

    @Override
    public void draw(EntropyManipulatorChain chain, @NotNull IRecipeSlotsView recipeSlotsView,
                      @NotNull GuiGraphics guiGraphics, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;

        for (int i = 0; i < chain.steps().size(); i++) {
            EntropyManipulatorChain.Step step = chain.steps().get(i);
            int gapX = i * STRIDE + SLOT_SIZE + SLOT_GAP;

            if (step.forwardType() != null) {
                Component forward = Component.literal(">> " + step.forwardCost() + "mB");
                guiGraphics.drawString(font, forward, gapX, ITEM_Y - 9, 0xFF000000 | step.forwardType().tint_to(), false);
            }
            if (step.backwardType() != null) {
                Component backward = Component.literal("<< " + step.backwardCost() + "mB");
                guiGraphics.drawString(font, backward, gapX, ITEM_Y + SLOT_SIZE + 1, 0xFF000000 | step.backwardType().tint_to(), false);
            }
        }
    }
}
