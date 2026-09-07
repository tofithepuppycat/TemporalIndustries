package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyManipulatorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyManipulatorMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;
import org.jetbrains.annotations.NotNull;

/** Textured GUI for the Entropy Manipulator: two vertical entropy tank bars (Chaos/Order), a
 * material liquid tank drawn underneath the background texture's transparent window, an
 * input/output slot pair, and a Chaos/Order progress bar icon over the gear baked into the texture. */
@SuppressWarnings("null")
public class EntropyManipulatorScreen extends AbstractContainerScreen<EntropyManipulatorMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/entropy_manipulator.png");
    private static final ResourceLocation CHAOS_ICON = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/chaos_progress_bar_.png");
    private static final ResourceLocation ORDER_ICON = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/order_progress_bar_.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    private static final int CHAOS_BAR_X = 10;
    private static final int ORDER_BAR_X = 18;
    private static final int BAR_Y = 8;
    private static final int BAR_WIDTH = 4;
    private static final int BAR_HEIGHT = 64;

    private static final int LIQUID_X = 37;
    private static final int LIQUID_Y = 16;
    private static final int LIQUID_WIDTH = 9;
    private static final int LIQUID_HEIGHT = 48;

    /** Public so the JEI integration (see client/jei) can place its "Show Recipes" click area over
     * the same region as the progress bar icon. */
    public static final int PROGRESS_X = 74;
    public static final int PROGRESS_Y = 26;
    public static final int PROGRESS_WIDTH = 34;
    public static final int PROGRESS_HEIGHT = 34;

    public EntropyManipulatorScreen(EntropyManipulatorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // Drawn before the background blit so the texture's transparent tank window shows it through.
        renderLiquidTank(guiGraphics);

        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        renderEntropyBar(guiGraphics, leftPos + CHAOS_BAR_X, topPos + BAR_Y,
                menu.getChaosFluidAmount(), menu.getEntropyTankCapacity(), Registration.CHAOS_FLUID_TYPE.get(), EntropyType.CHAOS.color());
        renderEntropyBar(guiGraphics, leftPos + ORDER_BAR_X, topPos + BAR_Y,
                menu.getOrderFluidAmount(), menu.getEntropyTankCapacity(), Registration.ORDER_FLUID_TYPE.get(), EntropyType.ORDER.color());

        renderProgress(guiGraphics);
    }

    /** Tiles the fluid's still texture bottom-up over the filled portion of the tank, tinted with the entropy color. */
    private void renderEntropyBar(GuiGraphics guiGraphics, int x, int y, int amount, int capacity, FluidType fluidType, int tintColor) {
        if (capacity <= 0 || amount <= 0) return;
        int filled = Math.max(1, Math.round((amount / (float) capacity) * BAR_HEIGHT));
        filled = Math.min(BAR_HEIGHT, filled);

        int bottom = y + BAR_HEIGHT;
        int top = bottom - filled;

        ResourceLocation stillTexture = IClientFluidTypeExtensions.of(fluidType).getStillTexture();
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(stillTexture);

        float r = ((tintColor >> 16) & 0xFF) / 255f;
        float g = ((tintColor >> 8) & 0xFF) / 255f;
        float b = (tintColor & 0xFF) / 255f;

        guiGraphics.enableScissor(x, top, x + BAR_WIDTH, bottom);
        for (int drawY = bottom - 16; drawY > top - 16; drawY -= 16) {
            guiGraphics.blit(x, drawY, 0, BAR_WIDTH, 16, sprite, r, g, b, 1f);
        }
        guiGraphics.disableScissor();
    }

    /** Renders the material liquid tank, tinted with the fluid's own natural color rather than a fixed entropy tint. */
    private void renderLiquidTank(GuiGraphics guiGraphics) {
        int amount = menu.getLiquidFluidAmount();
        int capacity = menu.getLiquidTankCapacity();
        if (amount <= 0 || capacity <= 0) return;

        Fluid fluid = BuiltInRegistries.FLUID.byId(menu.getLiquidFluidId());
        if (fluid == Fluids.EMPTY) return;

        FluidType fluidType = fluid.getFluidType();
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluidType);
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(extensions.getStillTexture());

        int tintColor = extensions.getTintColor();
        float r = ((tintColor >> 16) & 0xFF) / 255f;
        float g = ((tintColor >> 8) & 0xFF) / 255f;
        float b = (tintColor & 0xFF) / 255f;

        int filled = Math.max(1, Math.round((amount / (float) capacity) * LIQUID_HEIGHT));
        filled = Math.min(LIQUID_HEIGHT, filled);

        int x = leftPos + LIQUID_X;
        int bottom = topPos + LIQUID_Y + LIQUID_HEIGHT;
        int top = bottom - filled;

        guiGraphics.enableScissor(x, top, x + LIQUID_WIDTH, bottom);
        for (int drawY = bottom - 16; drawY > top - 16; drawY -= 16) {
            guiGraphics.blit(x, drawY, 0, LIQUID_WIDTH, 16, sprite, r, g, b, 1f);
        }
        guiGraphics.disableScissor();
    }

    /** Draws the Chaos or Order progress bar icon over the gear, growing left-to-right with progress. */
    private void renderProgress(GuiGraphics guiGraphics) {
        int code = menu.getActiveTypeCode();
        if (code == 0) return;

        EntropyType type = EntropyType.values()[code - 1];
        int width = Math.round((menu.getProgress() / (float) menu.getMaxProgress()) * PROGRESS_WIDTH);
        width = Math.clamp(width, 0, PROGRESS_WIDTH);
        if (width <= 0) return;

        ResourceLocation icon = type == EntropyType.CHAOS ? CHAOS_ICON : ORDER_ICON;
        int x = leftPos + PROGRESS_X;
        int y = topPos + PROGRESS_Y;

        guiGraphics.enableScissor(x, y, x + width, y + PROGRESS_HEIGHT);
        guiGraphics.blit(icon, x, y, 0, 0, PROGRESS_WIDTH, PROGRESS_HEIGHT, PROGRESS_WIDTH, PROGRESS_HEIGHT);
        guiGraphics.disableScissor();
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component machineTitle = Component.translatable("block.temporalindustries.entropy_manipulator");
        guiGraphics.drawString(font, machineTitle, (imageWidth - font.width(machineTitle)) / 2, 6, 0xFF3F3F3F, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);

        if (isOver(mouseX, mouseY, leftPos + CHAOS_BAR_X, topPos + BAR_Y, BAR_WIDTH, BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getChaosFluidAmount(), menu.getEntropyTankCapacity(), EntropyType.CHAOS), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + ORDER_BAR_X, topPos + BAR_Y, BAR_WIDTH, BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getOrderFluidAmount(), menu.getEntropyTankCapacity(), EntropyType.ORDER), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + LIQUID_X, topPos + LIQUID_Y, LIQUID_WIDTH, LIQUID_HEIGHT)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getLiquidFluidAmount() + "/" + menu.getLiquidTankCapacity() + " mB"), mouseX, mouseY);
        }
    }

    private boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static Component fluidTooltip(int amount, int capacity, EntropyType type) {
        return Component.literal(EntropyDisplay.formatFluid(amount) + "/" + EntropyDisplay.formatFluid(capacity))
                .append(EntropyDisplay.unit(type));
    }
}
