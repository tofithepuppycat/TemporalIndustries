package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyChangeInducerBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyChangeInducerMenu;
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

/** Textured GUI for the Entropy Change Inducer: two vertical entropy tank bars (Chaos/Order), a
 * material liquid tank drawn underneath the background texture's transparent window, an
 * input/output slot pair, and a progress indicator over the gear icon baked into the texture. */
@SuppressWarnings("null")
public class EntropyChangeInducerScreen extends AbstractContainerScreen<EntropyChangeInducerMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/entropy_change_inducer.png");
    private static final ResourceLocation CHAOS_ICON = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/chaos_progress_bar_.png");
    private static final ResourceLocation ORDER_ICON = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/order_progress_bar_.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    private static final int CHAOS_BAR_X = 10;
    private static final int ORDER_BAR_X = 18;
    private static final int BAR_Y = 8;
    private static final int BAR_WIDTH = 8;
    private static final int BAR_HEIGHT = 40;
    private static final int BAR_ICON_SIZE = 8;

    private static final int LIQUID_X = 37;
    private static final int LIQUID_Y = 16;
    private static final int LIQUID_SIZE = 16;

    private static final int PROGRESS_X = 74;
    private static final int PROGRESS_Y = 26;
    private static final int PROGRESS_WIDTH = 24;
    private static final int PROGRESS_HEIGHT = 24;

    public EntropyChangeInducerScreen(EntropyChangeInducerMenu menu, Inventory playerInventory, Component title) {
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

        guiGraphics.blit(CHAOS_ICON, leftPos + CHAOS_BAR_X, topPos + BAR_Y - 1, 0, 0, BAR_ICON_SIZE, BAR_ICON_SIZE, BAR_ICON_SIZE, BAR_ICON_SIZE);
        guiGraphics.blit(ORDER_ICON, leftPos + ORDER_BAR_X, topPos + BAR_Y - 1, 0, 0, BAR_ICON_SIZE, BAR_ICON_SIZE, BAR_ICON_SIZE, BAR_ICON_SIZE);

        renderProgress(guiGraphics);
    }

    /** Tiles the fluid's still texture (from the block atlas) bottom-up over the filled portion of
     * the tank, tinted with the entropy color — same technique as EntropyCondenserScreen. */
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

    /** Renders whatever fluid sits in the material liquid tank, tinted with its own natural color
     * (unlike the entropy bars, which always use the fixed Order/Chaos tint). */
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

        int filled = Math.max(1, Math.round((amount / (float) capacity) * LIQUID_SIZE));
        filled = Math.min(LIQUID_SIZE, filled);

        int x = leftPos + LIQUID_X;
        int bottom = topPos + LIQUID_Y + LIQUID_SIZE;
        int top = bottom - filled;

        guiGraphics.enableScissor(x, top, x + LIQUID_SIZE, bottom);
        for (int drawY = bottom - 16; drawY > top - 16; drawY -= 16) {
            guiGraphics.blit(x, drawY, 0, LIQUID_SIZE, 16, sprite, r, g, b, 1f);
        }
        guiGraphics.disableScissor();
    }

    /** Translucent tint over the gear icon that grows left-to-right with processing progress,
     * colored by whichever entropy type is currently fueling the active recipe. */
    private void renderProgress(GuiGraphics guiGraphics) {
        int code = menu.getActiveTypeCode();
        if (code == 0) return;

        EntropyType type = EntropyType.values()[code - 1];
        int width = Math.round((menu.getProgress() / (float) menu.getMaxProgress()) * PROGRESS_WIDTH);
        width = Math.clamp(width, 0, PROGRESS_WIDTH);
        if (width <= 0) return;

        int overlay = (0x60 << 24) | (type.color() & 0xFFFFFF);
        guiGraphics.fill(leftPos + PROGRESS_X, topPos + PROGRESS_Y, leftPos + PROGRESS_X + width, topPos + PROGRESS_Y + PROGRESS_HEIGHT, overlay);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, Component.translatable("block.temporalindustries.entropy_change_inducer"), 8, 6, 0xFFFFFF, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFFFFFF, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);

        if (isOver(mouseX, mouseY, leftPos + CHAOS_BAR_X, topPos + BAR_Y, BAR_WIDTH, BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getChaosFluidAmount() + " / " + menu.getEntropyTankCapacity() + " mB Chaos"), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + ORDER_BAR_X, topPos + BAR_Y, BAR_WIDTH, BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getOrderFluidAmount() + " / " + menu.getEntropyTankCapacity() + " mB Order"), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + LIQUID_X, topPos + LIQUID_Y, LIQUID_SIZE, LIQUID_SIZE)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getLiquidFluidAmount() + " / " + menu.getLiquidTankCapacity() + " mB"), mouseX, mouseY);
        }
    }

    private boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
