package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyCondenserBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.EntropyCondenserRangeClientState;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyCondenserMenu;
import io.github.tofithepuppycat.temporalindustries.network.EntropyCondenserSetRangePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/** Textured GUI for the Entropy Condenser: an FE energy bar, two vertical entropy tank bars
 * (Order/Chaos), a Cell input slot, a button cycling the absorb range, and the player inventory —
 * same layout/rendering technique as {@link EntropyManipulatorScreen}. */
@SuppressWarnings("null")
public class EntropyCondenserScreen extends AbstractContainerScreen<EntropyCondenserMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/entropy_condenser.png");
    private static final ResourceLocation ICON_EYE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_eye.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    private static final int ENERGY_BAR_X = 10;
    private static final int ENERGY_BAR_Y = 8;
    private static final int ENERGY_BAR_WIDTH = 4;
    private static final int ENERGY_BAR_HEIGHT = 62;
    private static final int ENERGY_COLOR = 0xFF3BFB98;

    private static final int ORDER_BAR_X = 57;
    private static final int CHAOS_BAR_X = 109;
    private static final int TANK_BAR_Y = 20;
    private static final int TANK_BAR_WIDTH = 9;
    private static final int TANK_BAR_HEIGHT = 46;

    private static final int ICON_SIZE = IconButtonRenderer.SIZE;
    private static final int ICON_Y = 66;
    private static final int SHOW_RANGE_ICON_X = IMAGE_WIDTH - ICON_SIZE - 4;
    private static final int RANGE_ICON_X = SHOW_RANGE_ICON_X - ICON_SIZE - 2;

    public EntropyCondenserScreen(EntropyCondenserMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight - 94;
    }

    private void cycleRange() {
        int next = menu.getRange() + 1;
        if (next > EntropyCondenserBlockEntity.MAX_RANGE) next = EntropyCondenserBlockEntity.MIN_RANGE;
        PacketDistributor.sendToServer(new EntropyCondenserSetRangePacket(menu.getBlockPos(), next));
    }

    private void toggleShowRange() {
        if (EntropyCondenserRangeClientState.isShowing(dimensionKey(), menu.getBlockPos())) {
            EntropyCondenserRangeClientState.clear();
        } else {
            EntropyCondenserRangeClientState.show(dimensionKey(), menu.getBlockPos());
        }
    }

    private ResourceLocation dimensionKey() {
        return Minecraft.getInstance().level.dimension().location();
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        renderEnergyBar(guiGraphics, leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y,
                menu.getEnergyStored(), menu.getEnergyCapacity());
        FluidBarRenderer.renderVertical(guiGraphics, leftPos + ORDER_BAR_X, topPos + TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT,
                menu.getOrderFluidAmount(), menu.getTankCapacity(), Registration.ORDER_FLUID_TYPE.get(), EntropyType.ORDER.color());
        FluidBarRenderer.renderVertical(guiGraphics, leftPos + CHAOS_BAR_X, topPos + TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT,
                menu.getChaosFluidAmount(), menu.getTankCapacity(), Registration.CHAOS_FLUID_TYPE.get(), EntropyType.CHAOS.color());

        renderRangeIcons(guiGraphics);
    }

    /** Range-cycle and show/hide-range controls, drawn as menu_icon_base_small.png icon buttons near
     * the panel's right edge rather than vanilla Buttons, matching the mod's inline-button look elsewhere. */
    private void renderRangeIcons(GuiGraphics guiGraphics) {
        int rangeX = leftPos + RANGE_ICON_X;
        int showRangeX = leftPos + SHOW_RANGE_ICON_X;
        int y = topPos + ICON_Y;

        IconButtonRenderer.renderBackground(guiGraphics, rangeX, y, 0);

        Component rangeLabel = Component.literal(String.valueOf(menu.getRange()));
        guiGraphics.drawCenteredString(font, rangeLabel, rangeX + ICON_SIZE / 2, y + (ICON_SIZE - 8) / 2, 0xFFFFFFFF);

        boolean showing = EntropyCondenserRangeClientState.isShowing(dimensionKey(), menu.getBlockPos());
        IconButtonRenderer.renderBackground(guiGraphics, showRangeX, y, showing ? 0x8055FF55 : 0);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_EYE_TEXTURE, showRangeX, y);
    }

    private void renderEnergyBar(GuiGraphics guiGraphics, int x, int y, int amount, int capacity) {
        if (capacity <= 0 || amount <= 0) return;
        int filled = Math.max(1, Math.round((amount / (float) capacity) * ENERGY_BAR_HEIGHT));
        filled = Math.min(ENERGY_BAR_HEIGHT, filled);
        int bottom = y + ENERGY_BAR_HEIGHT;
        guiGraphics.fill(x, bottom - filled, x + ENERGY_BAR_WIDTH, bottom, ENERGY_COLOR);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component machineTitle = Component.translatable("block.temporalindustries.entropy_condenser");
        guiGraphics.drawString(font, machineTitle, (imageWidth - font.width(machineTitle)) / 2, 6, 0xFF3F3F3F, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);

        if (isOver(mouseX, mouseY, leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y, ENERGY_BAR_WIDTH, ENERGY_BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getEnergyStored() + "/" + menu.getEnergyCapacity() + " FE"), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + ORDER_BAR_X, topPos + TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getOrderFluidAmount(), menu.getTankCapacity(), EntropyType.ORDER), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + CHAOS_BAR_X, topPos + TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getChaosFluidAmount(), menu.getTankCapacity(), EntropyType.CHAOS), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + RANGE_ICON_X, topPos + ICON_Y, ICON_SIZE, ICON_SIZE)) {
            int range = menu.getRange();
            guiGraphics.renderTooltip(font, Component.literal("Range: " + range + "x" + range + "x" + range), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + SHOW_RANGE_ICON_X, topPos + ICON_Y, ICON_SIZE, ICON_SIZE)) {
            boolean showing = EntropyCondenserRangeClientState.isShowing(dimensionKey(), menu.getBlockPos());
            guiGraphics.renderTooltip(font, Component.literal(showing ? "Hide Range" : "Show Range"), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (isOver((int) mouseX, (int) mouseY, leftPos + RANGE_ICON_X, topPos + ICON_Y, ICON_SIZE, ICON_SIZE)) {
                cycleRange();
                return true;
            }
            if (isOver((int) mouseX, (int) mouseY, leftPos + SHOW_RANGE_ICON_X, topPos + ICON_Y, ICON_SIZE, ICON_SIZE)) {
                toggleShowRange();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static Component fluidTooltip(int amount, int capacity, EntropyType type) {
        return EntropyDisplay.amountOverCapacity(amount, capacity, type);
    }
}
