package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyCondenserBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyCondenserMenu;
import io.github.tofithepuppycat.temporalindustries.network.EntropyCondenserSetRangePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/** Minimal placeholder GUI: an FE bar, two fluid tank bars, a Cell input slot, a button cycling the
 * absorb range, and the player inventory — drawn with plain fills (no texture atlas yet), same
 * "no dedicated art" approach the rest of this mod's newer machines use. */
@SuppressWarnings("null")
public class EntropyCondenserScreen extends AbstractContainerScreen<EntropyCondenserMenu> {
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 218;

    private static final int ENERGY_BAR_X = 12;
    private static final int ENERGY_BAR_Y = 20;
    private static final int BAR_WIDTH = 16;
    private static final int BAR_HEIGHT = 80;

    private static final int ORDER_BAR_X = 70;
    private static final int CHAOS_BAR_X = 100;

    private static final int SLOT_X = 140;
    private static final int SLOT_Y = 20;

    private static final int RANGE_BUTTON_X = 12;
    private static final int RANGE_BUTTON_Y = 106;
    private static final int RANGE_BUTTON_WIDTH = 130;
    private static final int RANGE_BUTTON_HEIGHT = 16;

    private Button rangeButton;

    public EntropyCondenserScreen(EntropyCondenserMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight - 96;
    }

    @Override
    protected void init() {
        super.init();
        rangeButton = Button.builder(rangeLabel(menu.getRange()), btn -> cycleRange())
                .pos(leftPos + RANGE_BUTTON_X, topPos + RANGE_BUTTON_Y)
                .size(RANGE_BUTTON_WIDTH, RANGE_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(rangeButton);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        rangeButton.setMessage(rangeLabel(menu.getRange()));
    }

    private void cycleRange() {
        int next = menu.getRange() + 1;
        if (next > EntropyCondenserBlockEntity.MAX_RANGE) next = EntropyCondenserBlockEntity.MIN_RANGE;
        PacketDistributor.sendToServer(new EntropyCondenserSetRangePacket(menu.getBlockPos(), next));
    }

    private static Component rangeLabel(int range) {
        return Component.literal("Range: " + range + "x" + range + "x" + range);
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF303030);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF1E1E1E);

        guiGraphics.fill(leftPos + SLOT_X - 1, topPos + SLOT_Y - 1, leftPos + SLOT_X + 17, topPos + SLOT_Y + 17, 0xFF8B8B8B);
        guiGraphics.fill(leftPos + SLOT_X, topPos + SLOT_Y, leftPos + SLOT_X + 16, topPos + SLOT_Y + 16, 0xFF373737);

        renderBar(guiGraphics, leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y,
                menu.getEnergyStored(), menu.getEnergyCapacity(), 0xFF4DD0E1);
        renderFluidBar(guiGraphics, leftPos + ORDER_BAR_X, topPos + ENERGY_BAR_Y,
                menu.getOrderFluidAmount(), menu.getTankCapacity(), Registration.ORDER_FLUID_TYPE.get(), EntropyType.ORDER.color());
        renderFluidBar(guiGraphics, leftPos + CHAOS_BAR_X, topPos + ENERGY_BAR_Y,
                menu.getChaosFluidAmount(), menu.getTankCapacity(), Registration.CHAOS_FLUID_TYPE.get(), EntropyType.CHAOS.color());
    }

    private void renderBar(GuiGraphics guiGraphics, int x, int y, int amount, int capacity, int color) {
        guiGraphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF000000);
        if (capacity <= 0 || amount <= 0) return;
        int filled = Math.max(1, Math.round((amount / (float) capacity) * (BAR_HEIGHT - 2)));
        filled = Math.min(BAR_HEIGHT - 2, filled);
        guiGraphics.fill(x + 1, y + BAR_HEIGHT - 1 - filled, x + BAR_WIDTH - 1, y + BAR_HEIGHT - 1, color);
    }

    /** Tiles the fluid's still texture (from the block atlas, so its animation frames advance
     * automatically) bottom-up over the filled portion of the tank, tinted with the entropy color -
     * anchored/scissored rather than stretched so a 16px-square texture tile stays undistorted. */
    private void renderFluidBar(GuiGraphics guiGraphics, int x, int y, int amount, int capacity, FluidType fluidType, int tintColor) {
        guiGraphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF000000);
        if (capacity <= 0 || amount <= 0) return;
        int filled = Math.max(1, Math.round((amount / (float) capacity) * (BAR_HEIGHT - 2)));
        filled = Math.min(BAR_HEIGHT - 2, filled);

        int innerX = x + 1;
        int innerWidth = BAR_WIDTH - 2;
        int bottom = y + BAR_HEIGHT - 1;
        int top = bottom - filled;

        ResourceLocation stillTexture = IClientFluidTypeExtensions.of(fluidType).getStillTexture();
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(stillTexture);

        float r = ((tintColor >> 16) & 0xFF) / 255f;
        float g = ((tintColor >> 8) & 0xFF) / 255f;
        float b = (tintColor & 0xFF) / 255f;

        guiGraphics.enableScissor(innerX, top, innerX + innerWidth, bottom);
        for (int drawY = bottom - 16; drawY > top - 16; drawY -= 16) {
            guiGraphics.blit(innerX, drawY, 0, innerWidth, 16, sprite, r, g, b, 1f);
        }
        guiGraphics.disableScissor();
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, Component.translatable("block.temporalindustries.entropy_condenser"), 8, 6, 0xFFFFFF, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFFFFFF, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);

        if (isOver(mouseX, mouseY, leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getEnergyStored() + " / " + menu.getEnergyCapacity() + " FE"), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + ORDER_BAR_X, topPos + ENERGY_BAR_Y)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getOrderFluidAmount() + " / " + menu.getTankCapacity() + " mB Order"), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + CHAOS_BAR_X, topPos + ENERGY_BAR_Y)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getChaosFluidAmount() + " / " + menu.getTankCapacity() + " mB Chaos"), mouseX, mouseY);
        }
    }

    private boolean isOver(int mouseX, int mouseY, int barX, int barY) {
        return mouseX >= barX && mouseX <= barX + BAR_WIDTH && mouseY >= barY && mouseY <= barY + BAR_HEIGHT;
    }
}
