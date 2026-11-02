package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ShortEnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropicBatteryMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Textured GUI for the Entropic Battery: a cell input slot, a cell output slot, two fluid tank bars, and the player inventory. */
@SuppressWarnings("null")
public class EntropicBatteryScreen extends AbstractContainerScreen<EntropicBatteryMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/entropic_battery.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    private static final int ORDER_BAR_X = 56;
    private static final int CHAOS_BAR_X = 108;
    private static final int TANK_BAR_Y = 19;
    private static final int TANK_BAR_WIDTH = 11;
    private static final int TANK_BAR_HEIGHT = 48;

    /** The energy bars' frames are part of the texture; the fill sits one pixel inside them. */
    private static final int LEFT_ENERGY_BAR_X = 36;
    private static final int RIGHT_ENERGY_BAR_X = IMAGE_WIDTH - LEFT_ENERGY_BAR_X - 6;
    private static final int ENERGY_BAR_Y = 19;
    private static final int ENERGY_BAR_WIDTH = 6;
    private static final int ENERGY_BAR_HEIGHT = 48;
    private static final int ENERGY_COLOR = 0xFF3BFB98;

    public EntropicBatteryScreen(EntropicBatteryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        InnerBackground.render(guiGraphics, leftPos, topPos, IMAGE_WIDTH, IMAGE_HEIGHT);
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        FluidBarRenderer.renderVertical(guiGraphics, leftPos + ORDER_BAR_X, topPos + TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT,
                menu.getOrderFluidAmount(), menu.getTankCapacity(), Registration.ORDER_FLUID_TYPE.get(), EntropyType.ORDER.color());
        FluidBarRenderer.renderVertical(guiGraphics, leftPos + CHAOS_BAR_X, topPos + TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT,
                menu.getChaosFluidAmount(), menu.getTankCapacity(), Registration.CHAOS_FLUID_TYPE.get(), EntropyType.CHAOS.color());
        renderEnergyBar(guiGraphics, LEFT_ENERGY_BAR_X);
        renderEnergyBar(guiGraphics, RIGHT_ENERGY_BAR_X);
    }

    private void renderEnergyBar(GuiGraphics guiGraphics, int xOffset) {
        int capacity = menu.getEnergyCapacity();
        int amount = menu.getEnergyStored();
        if (capacity <= 0 || amount <= 0) return;
        int innerHeight = ENERGY_BAR_HEIGHT - 2;
        int filled = Math.min(innerHeight, Math.max(1, Math.round(amount / (float) capacity * innerHeight)));
        int x = leftPos + xOffset + 1;
        int bottom = topPos + ENERGY_BAR_Y + ENERGY_BAR_HEIGHT - 1;
        guiGraphics.fill(x, bottom - filled, x + ENERGY_BAR_WIDTH - 2, bottom, ENERGY_COLOR);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component machineTitle = Component.translatable("block.temporalindustries.entropic_battery");
        guiGraphics.drawString(font, machineTitle, (imageWidth - font.width(machineTitle)) / 2, 6, 0xFF3F3F3F, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (isHovering(ORDER_BAR_X, TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT, mouseX, mouseY)) {
            guiGraphics.renderComponentTooltip(font, fluidTooltip(menu.getOrderFluidAmount(), menu.getTankCapacity(), EntropyType.ORDER), mouseX, mouseY);
        } else if (isHovering(CHAOS_BAR_X, TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT, mouseX, mouseY)) {
            guiGraphics.renderComponentTooltip(font, fluidTooltip(menu.getChaosFluidAmount(), menu.getTankCapacity(), EntropyType.CHAOS), mouseX, mouseY);
        } else if (isHovering(LEFT_ENERGY_BAR_X, ENERGY_BAR_Y, ENERGY_BAR_WIDTH, ENERGY_BAR_HEIGHT, mouseX, mouseY)
                || isHovering(RIGHT_ENERGY_BAR_X, ENERGY_BAR_Y, ENERGY_BAR_WIDTH, ENERGY_BAR_HEIGHT, mouseX, mouseY)) {
            guiGraphics.renderComponentTooltip(font, energyTooltip(), mouseX, mouseY);
        }
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private List<Component> energyTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(ShortEnergyDisplay.format(menu.getEnergyStored()) + "/" + ShortEnergyDisplay.format(menu.getEnergyCapacity())));
        if (menu.getBatteryCount() > 1) {
            lines.add(Component.translatable("block.temporalindustries.entropic_battery.combined", menu.getBatteryCount())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private List<Component> fluidTooltip(int amount, int capacity, EntropyType type) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(EntropyDisplay.formatFluid(amount) + "/" + EntropyDisplay.formatFluid(capacity))
                .append(EntropyDisplay.unit(type)));
        if (menu.getBatteryCount() > 1) {
            lines.add(Component.translatable("block.temporalindustries.entropic_battery.combined", menu.getBatteryCount())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }
}
