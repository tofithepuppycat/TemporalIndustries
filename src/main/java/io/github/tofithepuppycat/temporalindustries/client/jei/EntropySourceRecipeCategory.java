package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * JEI category for {@link EntropySource}: what goes in (an orb, items or nothing), the block that
 * does it, and the Order or Chaos that comes out, with the amounts and conditions written below.
 * Registered first so it is the tab a lookup of the Order or Chaos fluid opens on.
 */
public class EntropySourceRecipeCategory implements IRecipeCategory<EntropySource> {
    private static final ResourceLocation ORB_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/experience_orb.png");
    private static final int ORB_TEXTURE_SIZE = 64;
    private static final int ORB_TILE_SIZE = 16;

    private static final int WIDTH = 150;
    private static final int HEIGHT = 70;
    private static final int TITLE_Y = 2;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_Y = 14;
    private static final int INPUT_X = 2;
    private static final int MACHINE_X = 26;
    private static final int ARROW_X = 50;
    private static final int ARROW_Y = SLOT_Y + 1;
    private static final int OUTPUT_X = 84;
    private static final int DETAILS_Y = 36;
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_DETAIL_LINES = 3;
    private static final int TEXT_COLOR = 0xFF404040;

    private final IDrawable icon;
    private final IDrawable arrow;
    private final IDrawable slot;

    public EntropySourceRecipeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(Registration.ENTROPY_CONDENSER_ITEM.get().getDefaultInstance());
        this.arrow = guiHelper.getRecipeArrow();
        this.slot = guiHelper.getSlotDrawable();
    }

    @Override
    public RecipeType<EntropySource> getRecipeType() {
        return TemporalIndustriesJeiPlugin.ENTROPY_SOURCE_RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.temporalindustries.entropy_source");
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
    public void setRecipe(IRecipeLayoutBuilder builder, EntropySource source, IFocusGroup focuses) {
        if (!source.inputs().isEmpty()) {
            builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + 1, SLOT_Y + 1)
                    .setStandardSlotBackground()
                    .addItemStacks(source.inputs());
        } else if (!source.icons().isEmpty()) {
            builder.addSlot(RecipeIngredientRole.RENDER_ONLY, INPUT_X + 1, SLOT_Y + 1)
                    .setStandardSlotBackground()
                    .addItemStacks(source.icons());
        }

        ItemStack machine = source.machine();
        if (!machine.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.CATALYST, MACHINE_X + 1, SLOT_Y + 1)
                    .setStandardSlotBackground()
                    .addItemStack(machine);
        }

        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X + 1, SLOT_Y + 1)
                .setStandardSlotBackground()
                .addFluidStack(EntropyFluids.fluid(source.type()), source.fluidMb());
    }

    @Override
    public void draw(EntropySource source, @NotNull IRecipeSlotsView recipeSlotsView, @NotNull GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        int tint = 0xFF000000 | source.type().tint_to();
        guiGraphics.drawString(font, source.name(), INPUT_X, TITLE_Y, tint, false);

        if (source.orbInput()) {
            slot.draw(guiGraphics, INPUT_X, SLOT_Y);
            int color = source.type().color();
            guiGraphics.setColor(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F);
            guiGraphics.blit(ORB_TEXTURE, INPUT_X + 1, SLOT_Y + 1, 0, ORB_TILE_SIZE, ORB_TILE_SIZE, ORB_TILE_SIZE,
                    ORB_TEXTURE_SIZE, ORB_TEXTURE_SIZE);
            guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        arrow.draw(guiGraphics, ARROW_X, ARROW_Y);
        guiGraphics.drawString(font, source.fluidMb() + " mB", OUTPUT_X + SLOT_SIZE + 4,
                SLOT_Y + (SLOT_SIZE - font.lineHeight) / 2, tint, false);

        int y = DETAILS_Y;
        int lines = 0;
        for (Component detail : source.details()) {
            for (FormattedCharSequence line : font.split(detail, WIDTH - 2 * INPUT_X)) {
                if (lines++ >= MAX_DETAIL_LINES) return;
                guiGraphics.drawString(font, line, INPUT_X, y, TEXT_COLOR, false);
                y += LINE_HEIGHT;
            }
        }
    }

    @Override
    public ResourceLocation getRegistryName(@NotNull EntropySource source) {
        return ResourceLocation.fromNamespaceAndPath(
                io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID,
                "entropy_source/" + source.type().getSerializedName() + "/" + source.id());
    }
}
