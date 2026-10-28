package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyBatteryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/** Textured GUI for the Entropy Battery: a cell input slot, a cell output slot, two fluid tank bars, and the player inventory. */
@SuppressWarnings("null")
public class EntropyBatteryScreen extends AbstractContainerScreen<EntropyBatteryMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/entropy_battery.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;

    private static final int ORDER_BAR_X = 57;
    private static final int CHAOS_BAR_X = 109;
    private static final int TANK_BAR_Y = 20;
    private static final int TANK_BAR_WIDTH = 9;
    private static final int TANK_BAR_HEIGHT = 46;

    public EntropyBatteryScreen(EntropyBatteryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        FluidBarRenderer.renderVertical(guiGraphics, leftPos + ORDER_BAR_X, topPos + TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT,
                menu.getOrderFluidAmount(), menu.getTankCapacity(), Registration.ORDER_FLUID_TYPE.get(), EntropyType.ORDER.color());
        FluidBarRenderer.renderVertical(guiGraphics, leftPos + CHAOS_BAR_X, topPos + TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT,
                menu.getChaosFluidAmount(), menu.getTankCapacity(), Registration.CHAOS_FLUID_TYPE.get(), EntropyType.CHAOS.color());
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component machineTitle = Component.translatable("block.temporalindustries.entropy_battery");
        guiGraphics.drawString(font, machineTitle, (imageWidth - font.width(machineTitle)) / 2, 6, 0xFF3F3F3F, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (isHovering(ORDER_BAR_X, TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getOrderFluidAmount(), menu.getTankCapacity(), EntropyType.ORDER), mouseX, mouseY);
        } else if (isHovering(CHAOS_BAR_X, TANK_BAR_Y, TANK_BAR_WIDTH, TANK_BAR_HEIGHT, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getChaosFluidAmount(), menu.getTankCapacity(), EntropyType.CHAOS), mouseX, mouseY);
        }
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private static Component fluidTooltip(int amount, int capacity, EntropyType type) {
        return Component.literal(EntropyDisplay.formatFluid(amount) + "/" + EntropyDisplay.formatFluid(capacity))
                .append(EntropyDisplay.unit(type));
    }
}
