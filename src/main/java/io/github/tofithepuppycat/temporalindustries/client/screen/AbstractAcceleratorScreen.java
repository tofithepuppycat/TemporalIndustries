package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.menu.AbstractAcceleratorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * Shared "no dedicated art" placeholder GUI for the Decay/Despawn Accelerator screens, same flat-fill
 * approach as {@link CrudeEntropyCondenserScreen}: an input slot and a progress bar tinted by the
 * generator's entropy color, filled proportionally as the current item cooks down.
 */
@SuppressWarnings("null")
public abstract class AbstractAcceleratorScreen<T extends AbstractAcceleratorMenu> extends AbstractContainerScreen<T> {
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    private static final int SLOT_X = 80;
    private static final int SLOT_Y = 34;

    private static final int BAR_X = 80;
    private static final int BAR_Y = 56;
    private static final int BAR_WIDTH = 16;
    private static final int BAR_HEIGHT = 6;

    protected AbstractAcceleratorScreen(T menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight - 94;
    }

    protected abstract int entropyColor();

    protected abstract Component machineTitle();

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF8B8B8B);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFFC6C6C6);

        guiGraphics.fill(leftPos + SLOT_X - 1, topPos + SLOT_Y - 1, leftPos + SLOT_X + 17, topPos + SLOT_Y + 17, 0xFF8B8B8B);
        guiGraphics.fill(leftPos + SLOT_X, topPos + SLOT_Y, leftPos + SLOT_X + 16, topPos + SLOT_Y + 16, 0xFF373737);

        renderProgressBar(guiGraphics);
    }

    private void renderProgressBar(GuiGraphics guiGraphics) {
        int x = leftPos + BAR_X;
        int y = topPos + BAR_Y;
        guiGraphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF000000);

        int progress = menu.getProgress();
        int maxProgress = AbstractAcceleratorMenu.maxProgress();
        if (progress <= 0 || maxProgress <= 0) return;

        int filled = Math.max(1, Math.round((progress / (float) maxProgress) * (BAR_WIDTH - 2)));
        filled = Math.min(BAR_WIDTH - 2, filled);
        guiGraphics.fill(x + 1, y + 1, x + 1 + filled, y + BAR_HEIGHT - 1, 0xFF000000 | entropyColor());
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component title = machineTitle();
        guiGraphics.drawString(font, title, (imageWidth - font.width(title)) / 2, 6, 0xFF3F3F3F, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
    }
}
