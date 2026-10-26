package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.ReactorOutputHatchBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.WithergenicReactorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Flat-fill GUI for the Withergenic Reactor: the internal Order/Chaos balance bar to keep centred,
 * the output hatch's Chaos buffer, and the current efficiency and status.
 */
@SuppressWarnings("null")
public class WithergenicReactorScreen extends AbstractContainerScreen<WithergenicReactorMenu> {
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    private static final int BALANCE_BAR_X = 8;
    private static final int BALANCE_BAR_Y = 34;
    private static final int BALANCE_BAR_WIDTH = 124;
    private static final int BALANCE_BAR_HEIGHT = 10;

    private static final int BUFFER_BAR_X = 142;
    private static final int BUFFER_BAR_Y = 18;
    private static final int BUFFER_BAR_WIDTH = 26;
    private static final int BUFFER_BAR_HEIGHT = 58;

    private static final int TEXT_COLOR = 0xFF3F3F3F;

    public WithergenicReactorScreen(WithergenicReactorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF8B8B8B);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFFC6C6C6);

        EntropyBalanceBar.render(guiGraphics, leftPos + BALANCE_BAR_X, topPos + BALANCE_BAR_Y,
                BALANCE_BAR_WIDTH, BALANCE_BAR_HEIGHT, menu.getBalance());

        int bx = leftPos + BUFFER_BAR_X;
        int by = topPos + BUFFER_BAR_Y;
        guiGraphics.fill(bx, by, bx + BUFFER_BAR_WIDTH, by + BUFFER_BAR_HEIGHT, 0xFF000000);
        FluidBarRenderer.renderVertical(guiGraphics, bx + 1, by + 1, BUFFER_BAR_WIDTH - 2, BUFFER_BAR_HEIGHT - 2,
                menu.getBufferAmount(), ReactorOutputHatchBlockEntity.BUFFER_CAPACITY,
                Registration.CHAOS_FLUID_TYPE.get(), EntropyType.CHAOS.color());

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlot(guiGraphics, 8 + col * 18, 84 + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawSlot(guiGraphics, 8 + col * 18, 142);
        }
    }

    private void drawSlot(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(leftPos + x - 1, topPos + y - 1, leftPos + x + 17, topPos + y + 17, 0xFF8B8B8B);
        guiGraphics.fill(leftPos + x, topPos + y, leftPos + x + 16, topPos + y + 16, 0xFF373737);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, title, 8, 6, TEXT_COLOR, false);
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.withergenic_reactor.balance"), BALANCE_BAR_X, BALANCE_BAR_Y - 11, TEXT_COLOR, false);
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.withergenic_reactor.efficiency", menu.getEfficiencyPercent()), BALANCE_BAR_X, 50, TEXT_COLOR, false);
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.withergenic_reactor.output", menu.getOutputPerTick()), BALANCE_BAR_X, 61, TEXT_COLOR, false);
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.withergenic_reactor.status." + menu.getStatus()), BALANCE_BAR_X, 72, TEXT_COLOR, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, TEXT_COLOR, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (isHovering(BALANCE_BAR_X, BALANCE_BAR_Y, BALANCE_BAR_WIDTH, BALANCE_BAR_HEIGHT, mouseX, mouseY)) {
            guiGraphics.renderComponentTooltip(font, List.of(
                    Component.translatable("gui.temporalindustries.withergenic_reactor.balance_tooltip",
                            String.format("%+.2f", menu.getBalance()))), mouseX, mouseY);
        } else if (isHovering(BUFFER_BAR_X, BUFFER_BAR_Y, BUFFER_BAR_WIDTH, BUFFER_BAR_HEIGHT, mouseX, mouseY)) {
            guiGraphics.renderComponentTooltip(font, List.of(
                    Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                                    EntropyDisplay.formatFluid(menu.getBufferAmount()),
                                    EntropyDisplay.formatFluid(ReactorOutputHatchBlockEntity.BUFFER_CAPACITY))
                            .append(EntropyDisplay.unit(EntropyType.CHAOS))), mouseX, mouseY);
        }
    }
}
