package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.menu.AbstractAcceleratorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * Shared textured GUI for the Decay/Despawn Accelerator screens: an input slot and a progress icon
 * drawn over its empty counterpart in the background, revealed from the bottom up as the item cooks down.
 */
@SuppressWarnings("null")
public abstract class AbstractAcceleratorScreen<T extends AbstractAcceleratorMenu> extends AbstractContainerScreen<T> {
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    private final ResourceLocation background;
    private final ResourceLocation progressIcon;
    private final int progressX;
    private final int progressY;
    private final int progressSize;

    protected AbstractAcceleratorScreen(T menu, Inventory playerInventory, Component title,
                                        ResourceLocation background, ResourceLocation progressIcon,
                                        int progressX, int progressY, int progressSize) {
        super(menu, playerInventory, title);
        this.background = background;
        this.progressIcon = progressIcon;
        this.progressX = progressX;
        this.progressY = progressY;
        this.progressSize = progressSize;
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight - 94;
    }

    protected abstract Component machineTitle();

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(background, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        renderProgressIcon(guiGraphics);
    }

    private void renderProgressIcon(GuiGraphics guiGraphics) {
        int progress = menu.getProgress();
        int maxProgress = AbstractAcceleratorMenu.maxProgress();
        if (progress <= 0 || maxProgress <= 0) return;

        int filled = Math.clamp(Math.round((progress / (float) maxProgress) * progressSize), 1, progressSize);
        int skipped = progressSize - filled;
        guiGraphics.blit(progressIcon, leftPos + progressX, topPos + progressY + skipped,
                0, skipped, progressSize, filled, progressSize, progressSize);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component title = machineTitle();
        guiGraphics.drawString(font, title, (imageWidth - font.width(title)) / 2, 6, 0xFF3F3F3F, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
    }
}
