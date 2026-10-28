package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyBalancerBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyBalancerMenu;
import io.github.tofithepuppycat.temporalindustries.network.EntropyBalancerConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * GUI for the Entropy Balancer on its own panel with the player inventory below: the two
 * buffers at the sides, the target balance in the middle with step buttons, and a gauge showing the
 * bound output's balance against the target.
 */
@SuppressWarnings("null")
public class EntropyBalancerScreen extends AbstractContainerScreen<EntropyBalancerMenu> {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/balancer.png");

    private static final int IMAGE_SIZE = 256;
    /** balancer.png's artwork sits inset this many pixels within its canvas; offsets below are measured from its edge. */
    private static final int CONTENT_MARGIN = 16;
    private static final int CONTENT_SIZE = IMAGE_SIZE - 2 * CONTENT_MARGIN;
    private static final int CENTER_X = CONTENT_SIZE / 2;

    private static final int TEXT_PRIMARY = 0xFF2B2B2B;
    private static final int TEXT_MUTED = 0xFF6A6A6A;
    private static final int ORDER_TEXT = 0xFF7B52B8;
    private static final int CHAOS_TEXT = 0xFF0A8A95;
    private static final int WARNING_TEXT = 0xFFB06A00;
    private static final int COLOR_BORDER = 0xFF000000;

    private static final int TANK_Y = 20;
    private static final int TANK_WIDTH = 12;
    private static final int TANK_HEIGHT = 80;
    private static final int ORDER_TANK_X = 12;
    private static final int CHAOS_TANK_X = CONTENT_SIZE - 12 - TANK_WIDTH;
    private static final int TANK_LABEL_Y = TANK_Y + TANK_HEIGHT + 4;

    private static final int TARGET_LABEL_Y = 18;
    private static final int TARGET_VALUE_Y = 28;
    private static final float TARGET_VALUE_SCALE = 2.0F;
    private static final int STEP_ROW_Y = 46;
    private static final int STEP_BUTTON_WIDTH = 24;
    private static final int STEP_BUTTON_GAP = 2;
    private static final int BUTTON_HEIGHT = 16;
    private static final int[] STEPS = {-1000, -100, -10, 10, 100, 1000};
    private static final int RESET_Y = 66;
    private static final int RESET_WIDTH = 64;
    private static final int ACTION_GAP = 4;

    private static final int GAUGE_Y = 90;
    private static final int GAUGE_WIDTH = 140;
    private static final int GAUGE_HEIGHT = 8;
    private static final int GAUGE_CAPTION_Y = 102;
    private static final int STATUS_Y = 114;
    private static final int STATUS_WIDTH = 150;
    private static final int UNBIND_WIDTH = 64;

    public EntropyBalancerScreen(EntropyBalancerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_SIZE;
        imageHeight = IMAGE_SIZE;
        inventoryLabelY = imageHeight + 100; // Push off-screen to hide the inventory label
    }

    private int panelX() {
        return leftPos + CONTENT_MARGIN;
    }

    private int panelY() {
        return topPos + CONTENT_MARGIN;
    }

    private int stepButtonX(int index) {
        int total = STEPS.length * STEP_BUTTON_WIDTH + (STEPS.length - 1) * STEP_BUTTON_GAP;
        return panelX() + CENTER_X - total / 2 + index * (STEP_BUTTON_WIDTH + STEP_BUTTON_GAP);
    }

    /** Centered on its own, or beside the unbind button while an output is bound. */
    private int resetX() {
        return isBound()
                ? panelX() + CENTER_X - ACTION_GAP / 2 - RESET_WIDTH
                : panelX() + CENTER_X - RESET_WIDTH / 2;
    }

    private int unbindX() {
        return panelX() + CENTER_X + ACTION_GAP / 2;
    }

    private int gaugeX() {
        return panelX() + CENTER_X - GAUGE_WIDTH / 2;
    }

    private boolean isBound() {
        return menu.getStatus() != EntropyBalancerBlockEntity.OutputStatus.UNBOUND;
    }

    private void sendTarget(int target, boolean clearOutput) {
        PacketDistributor.sendToServer(new EntropyBalancerConfigPacket(menu.getBlockPos(), target, clearOutput));
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_SIZE, IMAGE_SIZE);

        renderTank(guiGraphics, ORDER_TANK_X, menu.getOrderFluidAmount(), EntropyType.ORDER);
        renderTank(guiGraphics, CHAOS_TANK_X, menu.getChaosFluidAmount(), EntropyType.CHAOS);
        drawCentered(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.order"),
                panelX() + ORDER_TANK_X + TANK_WIDTH / 2, panelY() + TANK_LABEL_Y, ORDER_TEXT);
        drawCentered(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.chaos"),
                panelX() + CHAOS_TANK_X + TANK_WIDTH / 2, panelY() + TANK_LABEL_Y, CHAOS_TEXT);

        renderTarget(guiGraphics, mouseX, mouseY);
        renderGauge(guiGraphics);
        renderStatus(guiGraphics, mouseX, mouseY);
    }

    private void renderTank(GuiGraphics guiGraphics, int xOffset, int amount, EntropyType type) {
        int x = panelX() + xOffset;
        int y = panelY() + TANK_Y;
        guiGraphics.fill(x - 1, y - 1, x + TANK_WIDTH + 1, y + TANK_HEIGHT + 1, COLOR_BORDER);
        guiGraphics.fill(x, y, x + TANK_WIDTH, y + TANK_HEIGHT, 0xFF2A2A33);
        FluidBarRenderer.renderVertical(guiGraphics, x, y, TANK_WIDTH, TANK_HEIGHT, amount, menu.getTankCapacity(),
                type == EntropyType.ORDER ? Registration.ORDER_FLUID_TYPE.get() : Registration.CHAOS_FLUID_TYPE.get(), type.color());
    }

    private void renderTarget(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int centerX = panelX() + CENTER_X;
        drawCentered(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.target"),
                centerX, panelY() + TARGET_LABEL_Y, TEXT_MUTED);

        int target = menu.getTarget();
        Component value = Component.literal(signed(target));
        int color = target == 0 ? TEXT_PRIMARY : (target < 0 ? ORDER_TEXT : CHAOS_TEXT);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerX, panelY() + TARGET_VALUE_Y, 0);
        guiGraphics.pose().scale(TARGET_VALUE_SCALE, TARGET_VALUE_SCALE, 1.0F);
        guiGraphics.drawString(font, value, -font.width(value) / 2, 0, color, false);
        guiGraphics.pose().popPose();

        for (int i = 0; i < STEPS.length; i++) {
            int step = STEPS[i];
            boolean atLimit = step < 0 ? target <= -EntropyBalancerBlockEntity.MAX_TARGET : target >= EntropyBalancerBlockEntity.MAX_TARGET;
            drawButton(guiGraphics, stepButtonX(i), panelY() + STEP_ROW_Y, STEP_BUTTON_WIDTH,
                    Component.literal((step > 0 ? "+" : "-") + EntropyDisplay.formatBalance(Math.abs(step)).replaceAll("\\.?0+$", "")),
                    !atLimit, mouseX, mouseY);
        }
        drawButton(guiGraphics, resetX(), panelY() + RESET_Y, RESET_WIDTH,
                Component.translatable("gui.temporalindustries.entropy_balancer.reset"), target != 0, mouseX, mouseY);
    }

    /** The output's balance as a bar from full Order to full Chaos, with a tick at the target. */
    private void renderGauge(GuiGraphics guiGraphics) {
        int x = gaugeX();
        int y = panelY() + GAUGE_Y;
        boolean active = menu.getStatus() == EntropyBalancerBlockEntity.OutputStatus.ACTIVE;
        EntropyBalanceBar.render(guiGraphics, x, y, GAUGE_WIDTH, GAUGE_HEIGHT,
                active ? Math.clamp(menu.getOutputOffset() / (float) EntropyBalancerBlockEntity.MAX_TARGET, -1.0F, 1.0F) : 0.0F);

        int half = GAUGE_WIDTH / 2 - 1;
        int markerX = x + GAUGE_WIDTH / 2 + Math.round(menu.getTarget() / (float) EntropyBalancerBlockEntity.MAX_TARGET * half);
        guiGraphics.fill(markerX, y - 3, markerX + 1, y + GAUGE_HEIGHT + 3, 0xFFFFC04D);

        Component caption = active
                ? Component.translatable("gui.temporalindustries.entropy_balancer.output_balance", signed(menu.getOutputOffset()))
                : Component.translatable("gui.temporalindustries.entropy_balancer.output_balance", "--");
        drawCentered(guiGraphics, caption, panelX() + CENTER_X, panelY() + GAUGE_CAPTION_Y, TEXT_MUTED);
    }

    private void renderStatus(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = panelX() + CENTER_X - STATUS_WIDTH / 2;
        int y = panelY() + STATUS_Y;
        switch (menu.getStatus()) {
            case UNBOUND -> drawWrapped(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.unbound"),
                    x, y, TEXT_MUTED);
            case UNREACHABLE -> {
                BlockPos pos = menu.getOutputPos();
                drawWrapped(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.unreachable",
                        pos.getX(), pos.getY(), pos.getZ()), x, y, WARNING_TEXT);
            }
            case ACTIVE -> {
                BlockPos pos = menu.getOutputPos();
                drawWrapped(guiGraphics, Component.translatable("gui.temporalindustries.entropy_balancer.bound",
                        pos.getX(), pos.getY(), pos.getZ()), x, y, TEXT_PRIMARY);
            }
        }
        if (isBound()) {
            drawButton(guiGraphics, unbindX(), panelY() + RESET_Y, UNBIND_WIDTH,
                    Component.translatable("gui.temporalindustries.entropy_balancer.unbind"), true, mouseX, mouseY);
        }
    }

    private void drawButton(GuiGraphics guiGraphics, int x, int y, int width, Component label, boolean enabled,
                            int mouseX, int mouseY) {
        boolean hovered = enabled && isOver(mouseX, mouseY, x, y, width, BUTTON_HEIGHT);
        int fill = !enabled ? 0xFF8A8A8A : (hovered ? 0xFF5A6E9A : 0xFF3F4F73);
        guiGraphics.fill(x, y, x + width, y + BUTTON_HEIGHT, COLOR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + BUTTON_HEIGHT - 1, fill);
        guiGraphics.drawCenteredString(font, label, x + width / 2, y + (BUTTON_HEIGHT - font.lineHeight) / 2 + 1,
                enabled ? 0xFFFFFFFF : 0xFFC8C8C8);
    }

    private void drawCentered(GuiGraphics guiGraphics, Component text, int centerX, int y, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private void drawWrapped(GuiGraphics guiGraphics, Component text, int x, int y, int color) {
        for (FormattedCharSequence line : font.split(text, STATUS_WIDTH)) {
            guiGraphics.drawString(font, line, x, y, color, false);
            y += font.lineHeight;
        }
    }

    /** A balance offset in the displayed 0.01 units with an explicit sign. */
    private static String signed(int raw) {
        return (raw > 0 ? "+" : "") + EntropyDisplay.formatBalance(raw);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component title = Component.translatable("block.temporalindustries.entropy_balancer");
        guiGraphics.drawString(font, title, CONTENT_MARGIN + CENTER_X - font.width(title) / 2, CONTENT_MARGIN + 8, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (isOver(mouseX, mouseY, panelX() + ORDER_TANK_X, panelY() + TANK_Y, TANK_WIDTH, TANK_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getOrderFluidAmount(), EntropyType.ORDER), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, panelX() + CHAOS_TANK_X, panelY() + TANK_Y, TANK_WIDTH, TANK_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getChaosFluidAmount(), EntropyType.CHAOS), mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, gaugeX(), panelY() + GAUGE_Y - 3, GAUGE_WIDTH, GAUGE_HEIGHT + 6)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.entropy_balancer.gauge_tooltip"), mouseX, mouseY);
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
            for (int i = 0; i < STEPS.length; i++) {
                if (isOver(mouseX, mouseY, stepButtonX(i), panelY() + STEP_ROW_Y, STEP_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                    sendTarget(Math.clamp((long) menu.getTarget() + STEPS[i],
                            -EntropyBalancerBlockEntity.MAX_TARGET, EntropyBalancerBlockEntity.MAX_TARGET), false);
                    return true;
                }
            }
            if (isOver(mouseX, mouseY, resetX(), panelY() + RESET_Y, RESET_WIDTH, BUTTON_HEIGHT)) {
                sendTarget(0, false);
                return true;
            }
            if (isBound() && isOver(mouseX, mouseY, unbindX(), panelY() + RESET_Y, UNBIND_WIDTH, BUTTON_HEIGHT)) {
                sendTarget(menu.getTarget(), true);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean isOver(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
