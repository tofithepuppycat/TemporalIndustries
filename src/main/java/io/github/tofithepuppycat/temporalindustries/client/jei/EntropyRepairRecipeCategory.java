package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyManipulatorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
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
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * JEI category for {@link EntropyRepair}: a worn item, the liquid Order that repairs it and the
 * whole item, laid out like the Entropy Manipulator's transmutation tab beside it.
 */
public class EntropyRepairRecipeCategory implements IRecipeCategory<EntropyRepair> {
    private static final int WIDTH = 130;
    private static final int HEIGHT = 48;

    private static final int INPUT_X = 2;
    private static final int INPUT_Y = 6;
    private static final int ARROW_X = 25;
    private static final int ARROW_Y = 10;
    private static final int OUTPUT_X = 93;
    private static final int OUTPUT_Y = 6;
    private static final int ENTROPY_X = 2;
    private static final int ENTROPY_Y = 26;
    private static final int AMOUNT_X = ENTROPY_X + 22;
    private static final int AMOUNT_Y = ENTROPY_Y + 3;
    private static final int SPEED_Y = ENTROPY_Y + 13;

    private final IDrawable icon;
    private final IDrawable arrow;

    public EntropyRepairRecipeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(Registration.ENTROPY_MANIPULATOR_ITEM.get().getDefaultInstance());
        this.arrow = guiHelper.getRecipeArrow();
    }

    @Override
    public RecipeType<EntropyRepair> getRecipeType() {
        return TemporalIndustriesJeiPlugin.ENTROPY_REPAIR_RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.temporalindustries.entropy_repair");
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
    public void setRecipe(IRecipeLayoutBuilder builder, EntropyRepair repair, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + 1, INPUT_Y + 1)
                .setStandardSlotBackground()
                .addItemStacks(repair.worn());
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, OUTPUT_Y + 1)
                .setStandardSlotBackground()
                .addItemStacks(repair.repaired());
        builder.addSlot(RecipeIngredientRole.INPUT, ENTROPY_X + 1, ENTROPY_Y + 1)
                .setStandardSlotBackground()
                .addFluidStack(Registration.ORDER_FLUID.get(), EntropyManipulatorBlockEntity.ORDER_MB_PER_DURABILITY);
    }

    @Override
    public void draw(EntropyRepair repair, @NotNull IRecipeSlotsView recipeSlotsView, @NotNull GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        arrow.draw(guiGraphics, ARROW_X, ARROW_Y);

        var font = Minecraft.getInstance().font;
        guiGraphics.drawString(font, Component.translatable("jei.temporalindustries.entropy_repair.cost",
                EntropyManipulatorBlockEntity.ORDER_MB_PER_DURABILITY), AMOUNT_X, AMOUNT_Y,
                0xFF000000 | EntropyType.ORDER.tint_to(), false);
        guiGraphics.drawString(font, Component.translatable("jei.temporalindustries.entropy_repair.speed",
                EntropyManipulatorBlockEntity.REPAIR_POINTS_PER_STEP,
                String.format(Locale.ROOT, "%.1f", EntropyManipulatorBlockEntity.REPAIR_STEP_TICKS / 20f)),
                AMOUNT_X, SPEED_Y, 0xFF808080, false);
    }

    @Override
    public ResourceLocation getRegistryName(@NotNull EntropyRepair repair) {
        return ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "entropy_repair");
    }
}
