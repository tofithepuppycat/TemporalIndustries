package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyBalancerBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.EnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyBalancerMenu;
import io.github.tofithepuppycat.temporalindustries.network.EntropyBalancerConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

/**
 * GUI for the Entropy Balancer on its own panel with the player inventory below: the two
 * buffers at the sides, the target balance in the middle with step buttons, and a gauge showing the
 * bound output's balance against the target.
 */
@SuppressWarnings("null")
public class EntropyBalancerScreen extends AbstractContainerScreen<EntropyBalancerMenu> {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/balancer.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;
    /** Offsets below are measured from the image's top-left corner; controls sit between the tanks, centred on this. */
    private static final int CENTER_X = IMAGE_WIDTH / 2;
    private static final int AREA_LEFT = 28;
    private static final int AREA_RIGHT = IMAGE_WIDTH - AREA_LEFT;

    private static final int TEXT_PRIMARY = 0xFF2B2B2B;
    private static final int TEXT_MUTED = 0xFF6A6A6A;
    private static final int EDIT_BOX_TEXT = 0xFFE0E0E0;
    private static final int ORDER_TEXT = 0xFF7B52B8;
    private static final int CHAOS_TEXT = 0xFF0A8A95;
    private static final int WARNING_TEXT = 0xFFB06A00;

    /** The tanks are part of the texture; these are the fluid areas. */
    private static final int TANK_Y = 9;
    private static final int TANK_WIDTH = 11;
    private static final int TANK_HEIGHT = 67;
    private static final int ORDER_TANK_X = 11;
    private static final int CHAOS_TANK_X = IMAGE_WIDTH - ORDER_TANK_X - TANK_WIDTH;

    private static final int TITLE_Y = 6;
    private static final int INPUT_Y = 39;
    private static final int INPUT_WIDTH = 64;
    private static final int INPUT_HEIGHT = 14;
    private static final int INPUT_MAX_LENGTH = 7;

    private static final int GAUGE_Y = 59;
    private static final int GAUGE_WIDTH = AREA_RIGHT - AREA_LEFT;
    private static final int GAUGE_HEIGHT = 8;
    private static final int GAUGE_MARKER_OVERHANG = 2;
    private static final int ENERGY_BAR_X = 56;
    private static final int ENERGY_BAR_Y = 73;
    private static final int ENERGY_BAR_WIDTH = 64;
    private static final int ENERGY_BAR_HEIGHT = 5;
    private static final int ENERGY_COLOR = 0xFF3BFB98;
    private static final int STATUS_Y = 15;

    private EditBox targetBox;

    public EntropyBalancerScreen(EntropyBalancerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight + 100; // Push off-screen to hide the inventory label
    }

    @Override
    protected void init() {
        super.init();
        String previous = targetBox == null ? null : targetBox.getValue();
        targetBox = new EditBox(font, leftPos + CENTER_X - INPUT_WIDTH / 2, topPos + INPUT_Y, INPUT_WIDTH, INPUT_HEIGHT,
                Component.translatable("gui.temporalindustries.entropy_balancer.target_input"));
        targetBox.setMaxLength(INPUT_MAX_LENGTH);
        targetBox.setFilter(text -> text.matches("[+-]?\\d*[.,]?\\d{0,2}"));
        targetBox.setResponder(text -> targetBox.setTextColor(targetColor(text)));
        targetBox.setValue(previous != null ? previous : formatTarget(menu.getTarget()));
        addRenderableWidget(targetBox);
    }

    private static String formatTarget(int raw) {
        return signed(raw);
    }

    /** Order-coloured for a negative value, Chaos-coloured for a positive one. */
    private static int targetColor(String text) {
        try {
            double value = Double.parseDouble(text.replace(',', '.'));
            return value == 0 ? EDIT_BOX_TEXT : (value < 0 ? ORDER_TEXT : CHAOS_TEXT);
        } catch (NumberFormatException e) {
            return EDIT_BOX_TEXT;
        }
    }

    /** Sends the typed target, clamped to the allowed range; unreadable text just restores the current target. */
    private void applyTargetInput() {
        String text = targetBox.getValue().replace(',', '.');
        try {
            int raw = (int) Math.round(Double.parseDouble(text) * 100.0);
            sendTarget(Math.clamp(raw, -EntropyBalancerBlockEntity.MAX_TARGET, EntropyBalancerBlockEntity.MAX_TARGET), false);
        } catch (NumberFormatException e) {
            targetBox.setValue(formatTarget(menu.getTarget()));
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // Follows the machine's target (after a clamp) except while the player is typing.
        if (!targetBox.isFocused() && !targetBox.getValue().equals(formatTarget(menu.getTarget()))) {
            targetBox.setValue(formatTarget(menu.getTarget()));
        }
    }

    @Override
    public void removed() {
        if (targetBox.isFocused()) applyTargetInput();
        super.removed();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (targetBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                applyTargetInput();
                targetBox.setFocused(false);
                return true;
            }
            // Typing 'e' (the inventory key) must edit the box rather than close the screen.
            if (keyCode != GLFW.GLFW_KEY_ESCAPE && (targetBox.keyPressed(keyCode, scanCode, modifiers) || targetBox.canConsumeInput())) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int gaugeX() {
        return leftPos + AREA_LEFT;
    }

    private void sendTarget(int target, boolean clearOutput) {
        PacketDistributor.sendToServer(new EntropyBalancerConfigPacket(menu.getBlockPos(), target, clearOutput));
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        InnerBackground.render(guiGraphics, leftPos, topPos, IMAGE_WIDTH, IMAGE_HEIGHT);
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        renderTank(guiGraphics, ORDER_TANK_X, menu.getOrderFluidAmount(), EntropyType.ORDER);
        renderTank(guiGraphics, CHAOS_TANK_X, menu.getChaosFluidAmount(), EntropyType.CHAOS);

        renderGauge(guiGraphics);
        renderEnergyBar(guiGraphics);
        renderStatus(guiGraphics);
    }

    private void renderTank(GuiGraphics guiGraphics, int xOffset, int amount, EntropyType type) {
        int x = leftPos + xOffset;
        int y = topPos + TANK_Y;
        FluidBarRenderer.renderVertical(guiGraphics, x, y, TANK_WIDTH, TANK_HEIGHT, amount, menu.getTankCapacity(),
                type == EntropyType.ORDER ? Registration.ORDER_FLUID_TYPE.get() : Registration.CHAOS_FLUID_TYPE.get(), type.color());
    }

    private void renderEnergyBar(GuiGraphics guiGraphics) {
        int capacity = menu.getEnergyCapacity();
        int amount = menu.getEnergyStored();
        if (capacity <= 0 || amount <= 0) return;
        int innerWidth = ENERGY_BAR_WIDTH - 2;
        int filled = Math.min(innerWidth, Math.max(1, Math.round(amount / (float) capacity * innerWidth)));
        int x = leftPos + ENERGY_BAR_X + 1;
        int y = topPos + ENERGY_BAR_Y + 1;
        guiGraphics.fill(x, y, x + filled, y + ENERGY_BAR_HEIGHT - 2, ENERGY_COLOR);
    }

    /** The output's balance as a bar from full Order to full Chaos, with a tick at the target. */
    private void renderGauge(GuiGraphics guiGraphics) {
        int x = gaugeX();
        int y = topPos + GAUGE_Y;
        boolean active = menu.getStatus() == EntropyBalancerBlockEntity.OutputStatus.ACTIVE;
        EntropyBalanceBar.render(guiGraphics, x, y, GAUGE_WIDTH, GAUGE_HEIGHT,
                active ? Math.clamp(menu.getOutputOffset() / (float) EntropyBalancerBlockEntity.MAX_TARGET, -1.0F, 1.0F) : 0.0F);

        int half = GAUGE_WIDTH / 2 - 1;
        int markerX = x + GAUGE_WIDTH / 2 + Math.round(menu.getTarget() / (float) EntropyBalancerBlockEntity.MAX_TARGET * half);
        guiGraphics.fill(markerX, y - GAUGE_MARKER_OVERHANG, markerX + 1, y + GAUGE_HEIGHT + GAUGE_MARKER_OVERHANG, 0xFFFFC04D);

    }

    private void renderStatus(GuiGraphics guiGraphics) {
        int centerX = leftPos + CENTER_X;
        int y = topPos + STATUS_Y;
        switch (menu.getStatus()) {
            case UNBOUND -> drawCentered(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.unbound"),
                    centerX, y, TEXT_MUTED);
            case UNREACHABLE -> drawCentered(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.unreachable"),
                    centerX, y, WARNING_TEXT);
            case ACTIVE -> {
                BlockPos pos = menu.getOutputPos();
                drawCentered(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.bound",
                        pos.getX(), pos.getY(), pos.getZ()), centerX, y, TEXT_PRIMARY);
            }
        }
    }

    private void drawCentered(GuiGraphics guiGraphics, Component text, int centerX, int y, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** A balance offset in the displayed 0.01 units with an explicit sign. */
    private static String signed(int raw) {
        return (raw > 0 ? "+" : "") + EntropyDisplay.formatBalance(raw);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component title = Component.translatable("block.temporalindustries.entropy_balancer");
        guiGraphics.drawString(font, title, CENTER_X - font.width(title) / 2, TITLE_Y, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (isOver(mouseX, mouseY, leftPos + ORDER_TANK_X, topPos + TANK_Y, TANK_WIDTH, TANK_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getOrderFluidAmount(), EntropyType.ORDER), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + CHAOS_TANK_X, topPos + TANK_Y, TANK_WIDTH, TANK_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getChaosFluidAmount(), EntropyType.CHAOS), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y, ENERGY_BAR_WIDTH, ENERGY_BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, Component.literal(EnergyDisplay.format(menu.getEnergyStored()) + "/"
                    + EnergyDisplay.format(menu.getEnergyCapacity()) + " FE"), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, gaugeX(), topPos + GAUGE_Y - GAUGE_MARKER_OVERHANG, GAUGE_WIDTH, GAUGE_HEIGHT + 2 * GAUGE_MARKER_OVERHANG)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.entropy_balancer.output_balance",
                    menu.getStatus() == EntropyBalancerBlockEntity.OutputStatus.ACTIVE ? signed(menu.getOutputOffset()) : "--"), mouseX, mouseY);
        }
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private Component fluidTooltip(int amount, EntropyType type) {
        return Component.literal(EntropyDisplay.formatFluid(amount) + "/" + EntropyDisplay.formatFluid(menu.getTankCapacity()))
                .append(EntropyDisplay.unit(type));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (targetBox.isFocused() && !targetBox.isMouseOver(mouseX, mouseY)) {
                applyTargetInput();
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean isOver(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
