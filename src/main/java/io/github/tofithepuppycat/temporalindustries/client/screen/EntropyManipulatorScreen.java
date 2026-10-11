package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyManipulatorMenu;
import io.github.tofithepuppycat.temporalindustries.network.EntropyManipulatorCycleRecipePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.network.PacketDistributor;
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

    /** Shown below the output slot only when the input has more than one matching recipe (e.g.
     * Order+sand can yield glass, sandstone, or gravel); click it to cycle the chosen output. */
    private static final int RECIPE_SELECT_X = 116;
    private static final int RECIPE_SELECT_Y = 58;
    private static final int RECIPE_SELECT_SIZE = 16;

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
        renderRecipeSelect(guiGraphics);
    }

    /** Draws the currently-selected output item as a ghost icon below the output slot when the
     * input is ambiguous, so the player can see (and click to cycle) what will be produced. */
    private void renderRecipeSelect(GuiGraphics guiGraphics) {
        if (menu.getRecipeOptionCount() <= 1) return;
        int itemId = menu.getSelectedRecipeOutputId();
        if (itemId < 0) return;

        Item item = BuiltInRegistries.ITEM.byId(itemId);
        if (item == Items.AIR) return;

        int x = leftPos + RECIPE_SELECT_X;
        int y = topPos + RECIPE_SELECT_Y;
        guiGraphics.renderItem(new ItemStack(item), x, y);
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
        } else if (menu.getRecipeOptionCount() > 1
                && isOver(mouseX, mouseY, leftPos + RECIPE_SELECT_X, topPos + RECIPE_SELECT_Y, RECIPE_SELECT_SIZE, RECIPE_SELECT_SIZE)) {
            int itemId = menu.getSelectedRecipeOutputId();
            Item item = itemId < 0 ? Items.AIR : BuiltInRegistries.ITEM.byId(itemId);
            guiGraphics.renderTooltip(font, Component.translatable(
                    "gui.temporalindustries.entropy_manipulator.recipe_select", item.getDescription()), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && menu.getRecipeOptionCount() > 1
                && isOver((int) mouseX, (int) mouseY, leftPos + RECIPE_SELECT_X, topPos + RECIPE_SELECT_Y, RECIPE_SELECT_SIZE, RECIPE_SELECT_SIZE)) {
            PacketDistributor.sendToServer(new EntropyManipulatorCycleRecipePacket(menu.getBlockPos()));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static Component fluidTooltip(int amount, int capacity, EntropyType type) {
        return Component.literal(EntropyDisplay.formatFluid(amount) + "/" + EntropyDisplay.formatFluid(capacity))
                .append(EntropyDisplay.unit(type));
    }
}
