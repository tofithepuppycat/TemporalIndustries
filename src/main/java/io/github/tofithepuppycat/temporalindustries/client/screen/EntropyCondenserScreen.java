package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyCondenserMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/** Minimal placeholder GUI: an FE bar and two fluid tank bars, drawn with plain fills (no texture
 * atlas yet) — same "no dedicated art" approach the rest of this mod's newer machines use. */
@SuppressWarnings("null")
public class EntropyCondenserScreen extends AbstractContainerScreen<EntropyCondenserMenu> {
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 130;

    private static final int ENERGY_BAR_X = 12;
    private static final int ENERGY_BAR_Y = 20;
    private static final int BAR_WIDTH = 16;
    private static final int BAR_HEIGHT = 80;

    private static final int ORDER_BAR_X = 70;
    private static final int CHAOS_BAR_X = 100;

    public EntropyCondenserScreen(EntropyCondenserMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight + 100;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF303030);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF1E1E1E);

        renderBar(guiGraphics, leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y,
                menu.getEnergyStored(), menu.getEnergyCapacity(), 0xFF4DD0E1);
        renderBar(guiGraphics, leftPos + ORDER_BAR_X, topPos + ENERGY_BAR_Y,
                menu.getOrderFluidAmount(), menu.getTankCapacity(), EntropyType.ORDER.color() | 0xFF000000);
        renderBar(guiGraphics, leftPos + CHAOS_BAR_X, topPos + ENERGY_BAR_Y,
                menu.getChaosFluidAmount(), menu.getTankCapacity(), EntropyType.CHAOS.color() | 0xFF000000);
    }

    private void renderBar(GuiGraphics guiGraphics, int x, int y, int amount, int capacity, int color) {
        guiGraphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF000000);
        if (capacity <= 0 || amount <= 0) return;
        int filled = Math.max(1, Math.round((amount / (float) capacity) * (BAR_HEIGHT - 2)));
        filled = Math.min(BAR_HEIGHT - 2, filled);
        guiGraphics.fill(x + 1, y + BAR_HEIGHT - 1 - filled, x + BAR_WIDTH - 1, y + BAR_HEIGHT - 1, color);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, Component.translatable("block.temporalindustries.entropy_condenser"), 8, 6, 0xFFFFFF, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

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
