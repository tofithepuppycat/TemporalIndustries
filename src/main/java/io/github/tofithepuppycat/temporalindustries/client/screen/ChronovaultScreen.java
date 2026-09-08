package io.github.tofithepuppycat.temporalindustries.client.screen;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.mojang.blaze3d.systems.RenderSystem;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.EnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.client.IconTabRenderer;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.ChronovaultMenu;
import io.github.tofithepuppycat.temporalindustries.network.RollbackChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineDeleteBranchPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineDeleteHistoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineToggleAutoTrackPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** GUI for the Chronovault block: renders its chunk's commit graph (via {@link TimelineGraphWidget})
 * and lets the player pick a point in time to jump to. */
@SuppressWarnings("null")
public class ChronovaultScreen extends AbstractContainerScreen<ChronovaultMenu> {
    private static final ResourceLocation INVENTORY_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/base.png");
    private static final ResourceLocation ICON_CONFIG_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/icon_config.png");
    private static final ResourceLocation ICON_EYE_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/icon_eye.png");
    private static final ResourceLocation ICON_JUMP_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/icon_jump.png");
    private static final ResourceLocation ICON_TRASHCAN_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/icon_trashcan.png");
    private static final int ICON_CONFIG_SIZE = 26;
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;
    /** base.png's artwork sits inset this many pixels within the 256x256 canvas; layout constants
     * below are measured from the artwork's edge via {@link #panelX()}/{@link #panelY()}. */
    private static final int CONTENT_MARGIN = 16;
    private static final int CONTENT_SIZE = TEXTURE_WIDTH - 2 * CONTENT_MARGIN;

    // base.png is a light panel, so text/UI accents are tuned for a light background.
    private static final int TEXT_PRIMARY = 0xFF2B2B2B;
    private static final int TEXT_SECONDARY = 0xFF5A5A5A;
    private static final int COLOR_BORDER = 0xFF000000;

    // Auto-track/settings tabs: a vertical stack overlapping the panel's right edge, drawn BEFORE
    // the panel background in render() so its edge tucks them in.
    private static final int TAB_ROW_SIZE = IconTabRenderer.SIZE;
    private static final int TAB_OVERLAP = 5;
    private static final int AUTO_TRACK_Y_OFFSET = 40;
    private static final int TAB_ROW_SPACING = 4;
    private static final int SETTINGS_Y_OFFSET = AUTO_TRACK_Y_OFFSET + TAB_ROW_SIZE + TAB_ROW_SPACING;
    private static final int DELETE_BUTTON_WIDTH = 140;
    private static final int DELETE_BUTTON_HEIGHT = 20;
    private static final int CONFIRM_BUTTON_WIDTH = 66;

    private static final int GRAPH_X_OFFSET = 8;
    private static final int GRAPH_Y_OFFSET = 18;
    private static final int GRAPH_WIDTH = CONTENT_SIZE - 2 * GRAPH_X_OFFSET;
    /** Sized to leave room below for the preview-time labels and button row within CONTENT_SIZE. */
    private static final int GRAPH_HEIGHT = 154;

    private static final int ENERGY_BAR_X_OFFSET = 160;
    private static final int ENERGY_BAR_Y_OFFSET = 7;
    private static final int ENERGY_BAR_WIDTH = 57;
    private static final int ENERGY_BAR_HEIGHT = 8;

    private static final int ENTROPY_BAR_X_OFFSET = 6;
    private static final int ENTROPY_BAR_Y_OFFSET = 7;
    private static final int ENTROPY_BAR_WIDTH = 52;
    private static final int ENTROPY_BAR_HEIGHT = 8;
    private static final int COLOR_ORDER = 0xFF000000 | EntropyType.ORDER.color();
    private static final int COLOR_CHAOS = 0xFF000000 | EntropyType.CHAOS.color();

    private static final int PREVIEW_CURRENT_Y_OFFSET = 176;
    private static final int PREVIEW_DIFF_Y_OFFSET = 186;
    private static final int BUTTON_ROW_Y_OFFSET = 200;
    private static final int ACTION_BUTTON_SIZE = IconButtonRenderer.SIZE;
    private static final int ACTION_BUTTON_GAP = 6;

    private static final int SYNC_INTERVAL_TICKS = 20;

    private final TimelineGraphWidget graphWidget = new TimelineGraphWidget();

    private int ticksSinceSync = 0;
    private boolean settingsOverlayOpen = false;
    /** Whether the settings overlay is showing the "are you sure" step; reset when the overlay closes. */
    private boolean deleteHistoryConfirmPending = false;

    private int autoTrackX;
    private int autoTrackY;
    private int settingsX;
    private int settingsY;
    private int showChangesX;
    private int showChangesY;
    private int jumpX;
    private int jumpY;
    private int deleteBranchX;
    private int deleteBranchY;

    public ChronovaultScreen(ChronovaultMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = TEXTURE_WIDTH;
        imageHeight = TEXTURE_HEIGHT;
        inventoryLabelY = imageHeight + 100; // Push off-screen to hide inventory
    }

    /** The visible top-left corner of base.png's artwork; layout offsets are measured from here. */
    private int panelX() {
        return leftPos + CONTENT_MARGIN;
    }

    private int panelY() {
        return topPos + CONTENT_MARGIN;
    }

    @Override
    protected void init() {
        super.init();

        int groupX = panelX() + (CONTENT_SIZE - (ACTION_BUTTON_SIZE * 3 + ACTION_BUTTON_GAP * 2)) / 2;
        showChangesX = groupX;
        jumpX = groupX + ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP;
        deleteBranchX = jumpX + ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP;
        showChangesY = panelY() + BUTTON_ROW_Y_OFFSET;
        jumpY = showChangesY;
        deleteBranchY = showChangesY;

        autoTrackX = panelX() + CONTENT_SIZE - TAB_OVERLAP;
        autoTrackY = panelY() + AUTO_TRACK_Y_OFFSET;
        settingsX = panelX() + CONTENT_SIZE - TAB_OVERLAP;
        settingsY = panelY() + SETTINGS_Y_OFFSET;

        TimelineProjectionManager.setActiveMachine(menu.getBlockPos());
        graphWidget.init(menu.getBlockPos());
        // Force a full response regardless of any cache from a previously viewed machine.
        PacketDistributor.sendToServer(
                new TimelinePreviewRequestPacket(menu.getBlockPos(), Long.MIN_VALUE, Long.MIN_VALUE));
    }

    @Override
    public void onClose() {
        graphWidget.onClose();
        super.onClose();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        TimelineProjectionManager.setCurrentGameTime(getCurrentGameTime());

        // Periodically re-fetch commits; the server skips the full reply when the head hasn't moved.
        ticksSinceSync++;
        if (ticksSinceSync >= SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            PacketDistributor.sendToServer(
                    new TimelinePreviewRequestPacket(menu.getBlockPos(), TimelineProjectionManager.getHeadCommitId(),
                            TimelineProjectionManager.getPreviewVersion()));
        }

    }

    private void toggleShowChanges() {
        TimelineProjectionManager.toggleShowChanges();
    }

    /** Mirrors the button rects renderSettingsOverlay draws, since they're plain fills rather than Button widgets. */
    private void handleSettingsOverlayClick(double mouseX, double mouseY) {
        int buttonY = panelY() + 130;

        if (deleteHistoryConfirmPending) {
            int confirmX = panelX() + (CONTENT_SIZE - CONFIRM_BUTTON_WIDTH * 2 - 6) / 2;
            int cancelX = confirmX + CONFIRM_BUTTON_WIDTH + 6;
            if (isMouseOverRect(mouseX, mouseY, confirmX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                PacketDistributor.sendToServer(new TimelineMachineDeleteHistoryPacket(menu.getBlockPos()));
                deleteHistoryConfirmPending = false;
                settingsOverlayOpen = false;
            } else if (isMouseOverRect(mouseX, mouseY, cancelX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                deleteHistoryConfirmPending = false;
            }
        } else {
            int buttonX = panelX() + (CONTENT_SIZE - DELETE_BUTTON_WIDTH) / 2;
            if (isMouseOverRect(mouseX, mouseY, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                deleteHistoryConfirmPending = true;
            }
        }
    }

    private void jumpAndClose() {
        if (!TimelineProjectionManager.hasSelection()) {
            return;
        }
        long target = TimelineProjectionManager.getSelectedGameTime();
        long targetCommitId = TimelineProjectionManager.getSelectedCommitId();
        PacketDistributor.sendToServer(new RollbackChunkPacket(menu.getBlockPos(), target, targetCommitId));
        TimelineProjectionManager.clearSelectedCommit();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    private void deleteSelectedBranch() {
        if (!TimelineProjectionManager.isSelectedBranchDeletable()) {
            return;
        }
        PacketDistributor.sendToServer(new TimelineMachineDeleteBranchPacket(menu.getBlockPos(), TimelineProjectionManager.getSelectedCommitId()));
        TimelineProjectionManager.clearSelectedCommit();
        // Deleting a branch doesn't move the chunk's head, so the server's "nothing changed" skip
        // would otherwise suppress a reply; force a full resync to pick up the removed commits.
        PacketDistributor.sendToServer(new TimelinePreviewRequestPacket(menu.getBlockPos(), Long.MIN_VALUE, Long.MIN_VALUE));
    }

    private static Component showChangesLabel(boolean enabled) {
        return Component.translatable(enabled
                ? "gui.temporalindustries.chronovault.hide_changes"
                : "gui.temporalindustries.chronovault.show_changes");
    }

    private long getCurrentGameTime() {
        if (minecraft == null || minecraft.level == null) {
            return 0L;
        }
        return minecraft.level.getGameTime();
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, INVENTORY_TEXTURE);
        guiGraphics.blit(INVENTORY_TEXTURE, leftPos, topPos, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        graphWidget.render(guiGraphics, font, panelX() + GRAPH_X_OFFSET, panelY() + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT);

        int energyStored = menu.getEnergyStored();
        int energyCapacity = menu.getEnergyCapacity();

        int barX = panelX() + ENERGY_BAR_X_OFFSET;
        int barY = panelY() + ENERGY_BAR_Y_OFFSET;

        guiGraphics.fill(barX, barY, barX + ENERGY_BAR_WIDTH, barY + ENERGY_BAR_HEIGHT, 0xFF000000);
        if (energyCapacity > 0 && energyStored > 0) {
            int filled = Math.max(1, Math.round((energyStored / (float) energyCapacity) * (ENERGY_BAR_WIDTH - 2)));
            guiGraphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + ENERGY_BAR_HEIGHT - 1, 0xFF3BFB98);
        }

        renderEntropyBar(guiGraphics);

        if (!settingsOverlayOpen) {
            renderActionButtons(guiGraphics, mouseX, mouseY);
        }
    }

    /** Show Changes / Jump: icon buttons; Show Changes tints on while active, Jump dims while nothing is selected. */
    private void renderActionButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean showChangesEnabled = TimelineProjectionManager.isShowChangesEnabled();
        int showChangesTint = showChangesEnabled ? 0xB0CC5555 : (isMouseOverShowChangesButton(mouseX, mouseY) ? 0x40000000 : 0);
        IconButtonRenderer.renderBackground(guiGraphics, showChangesX, showChangesY, showChangesTint);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_EYE_TEXTURE, showChangesX, showChangesY);

        boolean jumpActive = TimelineProjectionManager.hasSelection();
        int jumpTint = !jumpActive ? 0x80000000 : (isMouseOverJumpButton(mouseX, mouseY) ? 0x40FFFFFF : 0);
        IconButtonRenderer.renderBackground(guiGraphics, jumpX, jumpY, jumpTint);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_JUMP_TEXTURE, jumpX, jumpY);

        boolean deleteActive = TimelineProjectionManager.isSelectedBranchDeletable();
        int deleteTint = !deleteActive ? 0x80000000 : (isMouseOverDeleteBranchButton(mouseX, mouseY) ? 0x40FFFFFF : 0);
        IconButtonRenderer.renderBackground(guiGraphics, deleteBranchX, deleteBranchY, deleteTint);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_TRASHCAN_TEXTURE, deleteBranchX, deleteBranchY);
    }

    private boolean isMouseOverShowChangesButton(double mouseX, double mouseY) {
        return mouseX >= showChangesX && mouseX <= showChangesX + ACTION_BUTTON_SIZE
                && mouseY >= showChangesY && mouseY <= showChangesY + ACTION_BUTTON_SIZE;
    }

    private boolean isMouseOverJumpButton(double mouseX, double mouseY) {
        return mouseX >= jumpX && mouseX <= jumpX + ACTION_BUTTON_SIZE
                && mouseY >= jumpY && mouseY <= jumpY + ACTION_BUTTON_SIZE;
    }

    private boolean isMouseOverDeleteBranchButton(double mouseX, double mouseY) {
        return mouseX >= deleteBranchX && mouseX <= deleteBranchX + ACTION_BUTTON_SIZE
                && mouseY >= deleteBranchY && mouseY <= deleteBranchY + ACTION_BUTTON_SIZE;
    }

    private boolean isMouseOverEnergyBar(int mouseX, int mouseY) {
        int barX = panelX() + ENERGY_BAR_X_OFFSET;
        int barY = panelY() + ENERGY_BAR_Y_OFFSET;
        return mouseX >= barX && mouseX <= barX + ENERGY_BAR_WIDTH && mouseY >= barY && mouseY <= barY + ENERGY_BAR_HEIGHT;
    }

    /** Bidirectional order/chaos balance bar: fills from the center tick outward. */
    private void renderEntropyBar(GuiGraphics guiGraphics) {
        int barX = panelX() + ENTROPY_BAR_X_OFFSET;
        int barY = panelY() + ENTROPY_BAR_Y_OFFSET;
        int entropy = menu.getEntropy();
        int max = menu.getEntropyMax();

        guiGraphics.fill(barX, barY, barX + ENTROPY_BAR_WIDTH, barY + ENTROPY_BAR_HEIGHT, 0xFF000000);

        int mid = barX + ENTROPY_BAR_WIDTH / 2;
        int half = ENTROPY_BAR_WIDTH / 2 - 1;
        float balance = max > 0 ? (entropy - max / 2f) / (max / 2f) : 0f; // -1 (order) .. +1 (chaos)
        int filled = Math.round(Math.abs(balance) * half);
        if (filled > 0) {
            if (balance >= 0) {
                guiGraphics.fill(mid, barY + 1, mid + filled, barY + ENTROPY_BAR_HEIGHT - 1, COLOR_CHAOS);
            } else {
                guiGraphics.fill(mid - filled, barY + 1, mid, barY + ENTROPY_BAR_HEIGHT - 1, COLOR_ORDER);
            }
        }
        guiGraphics.fill(mid, barY, mid + 1, barY + ENTROPY_BAR_HEIGHT, 0xFF888888);
    }

    private boolean isMouseOverEntropyBar(int mouseX, int mouseY) {
        int barX = panelX() + ENTROPY_BAR_X_OFFSET;
        int barY = panelY() + ENTROPY_BAR_Y_OFFSET;
        return mouseX >= barX && mouseX <= barX + ENTROPY_BAR_WIDTH && mouseY >= barY && mouseY <= barY + ENTROPY_BAR_HEIGHT;
    }

    private void renderAutoTrackTab(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean enabled = menu.getBlockEntity().isAutoTrackingEnabled();
        boolean hovered = isMouseOverAutoTrackTab(mouseX, mouseY);
        int tint = enabled ? 0xB0CC5555 : (hovered ? 0x40000000 : 0);

        IconTabRenderer.renderBackground(guiGraphics, autoTrackX, autoTrackY, tint);

        // Placeholder icon: a filled "record" dot.
        int glyphColor = enabled ? 0xFFFFEDED : TEXT_PRIMARY;
        int dotSize = 10;
        int dotX = autoTrackX + (TAB_ROW_SIZE - dotSize) / 2 + IconTabRenderer.ICON_X_NUDGE;
        int dotY = autoTrackY + (TAB_ROW_SIZE - dotSize) / 2;
        guiGraphics.fill(dotX, dotY, dotX + dotSize, dotY + dotSize, glyphColor);
    }

    private boolean isMouseOverAutoTrackTab(double mouseX, double mouseY) {
        return mouseX >= autoTrackX && mouseX <= autoTrackX + TAB_ROW_SIZE
                && mouseY >= autoTrackY && mouseY <= autoTrackY + TAB_ROW_SIZE;
    }

    private static Component autoTrackTooltip(boolean enabled) {
        return Component.translatable(enabled
                ? "gui.temporalindustries.timeline_machine.auto_track_tooltip_on"
                : "gui.temporalindustries.timeline_machine.auto_track_tooltip_off");
    }

    private void renderSettingsTab(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean hovered = isMouseOverSettingsTab(mouseX, mouseY);
        int tint = hovered ? 0x40000000 : 0;

        IconTabRenderer.renderBackground(guiGraphics, settingsX, settingsY, tint);
        IconTabRenderer.renderIcon(guiGraphics, ICON_CONFIG_TEXTURE, ICON_CONFIG_SIZE, settingsX, settingsY);
    }

    private boolean isMouseOverSettingsTab(double mouseX, double mouseY) {
        return mouseX >= settingsX && mouseX <= settingsX + TAB_ROW_SIZE
                && mouseY >= settingsY && mouseY <= settingsY + TAB_ROW_SIZE;
    }

    private void renderSettingsOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.blit(INVENTORY_TEXTURE, leftPos, topPos, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.settings_title"),
                panelX() + CONTENT_SIZE / 2, panelY() + 16, TEXT_PRIMARY);

        drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.delete_history_hint"),
                panelX() + 20, panelY() + 60, CONTENT_SIZE - 40, TEXT_SECONDARY);

        int buttonX = panelX() + (CONTENT_SIZE - DELETE_BUTTON_WIDTH) / 2;
        int buttonY = panelY() + 130;

        if (deleteHistoryConfirmPending) {
            drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.delete_history_confirm_prompt"),
                    panelX() + CONTENT_SIZE / 2, buttonY - 14, 0xFFB03030);

            int confirmX = panelX() + (CONTENT_SIZE - CONFIRM_BUTTON_WIDTH * 2 - 6) / 2;
            int cancelX = confirmX + CONFIRM_BUTTON_WIDTH + 6;
            drawOverlayButton(guiGraphics, confirmX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.timeline_machine.delete_history_confirm_button"),
                    0xFF8A3A3A, isMouseOverRect(mouseX, mouseY, confirmX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
            drawOverlayButton(guiGraphics, cancelX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.timeline_machine.delete_history_cancel_button"),
                    0xFF3A3A3A, isMouseOverRect(mouseX, mouseY, cancelX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
        } else {
            drawOverlayButton(guiGraphics, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.timeline_machine.delete_history_button"),
                    0xFF8A3A3A, isMouseOverRect(mouseX, mouseY, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
        }
    }

    private void drawOverlayButton(GuiGraphics guiGraphics, int x, int y, int width, int height,
                                   Component label, int baseColor, boolean hovered) {
        int bg = hovered ? brighten(baseColor) : baseColor;
        guiGraphics.fill(x, y, x + width, y + height, COLOR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, bg);
        drawCenteredNoShadow(guiGraphics, label, x + width / 2, y + (height - 8) / 2, 0xFFFFFFFF);
    }

    private static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 30);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 30);
        int b = Math.min(255, (color & 0xFF) + 30);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static boolean isMouseOverRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private void drawCenteredNoShadow(GuiGraphics guiGraphics, Component text, int centerX, int y, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private void drawWordWrapNoShadow(GuiGraphics guiGraphics, Component text, int x, int y, int maxWidth, int color) {
        int lineY = y;
        for (FormattedCharSequence line : font.split(text, maxWidth)) {
            guiGraphics.drawString(font, line, x, lineY, color, false);
            lineY += font.lineHeight;
        }
    }

    private void renderTabs(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderAutoTrackTab(guiGraphics, mouseX, mouseY);
        renderSettingsTab(guiGraphics, mouseX, mouseY);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // Tabs are drawn BEFORE the panel background so its opaque texture tucks in their overlap.
        renderTabs(guiGraphics, mouseX, mouseY);

        // Skip super.render() to avoid rendering inventory slots
        renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderLabels(guiGraphics, mouseX, mouseY);
        for (var widget : renderables) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (settingsOverlayOpen) {
            renderSettingsOverlay(guiGraphics, mouseX, mouseY);
            return;
        }

        if (isMouseOverAutoTrackTab(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, autoTrackTooltip(menu.getBlockEntity().isAutoTrackingEnabled()), mouseX, mouseY);
            return;
        }
        if (isMouseOverSettingsTab(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.timeline_machine.settings_tooltip"), mouseX, mouseY);
            return;
        }

        List<FormattedCharSequence> tooltip = graphWidget.getTooltipAt(mouseX, mouseY);
        if (!tooltip.isEmpty()) {
            guiGraphics.renderTooltip(font, tooltip, mouseX, mouseY);
        } else if (isMouseOverEnergyBar(mouseX, mouseY)) {
            Component tooltipComponent = Component.literal(EnergyDisplay.format(menu.getEnergyStored()) + "/" + EnergyDisplay.format(menu.getEnergyCapacity()) + " FE");
            guiGraphics.renderTooltip(font, tooltipComponent, mouseX, mouseY);
        } else if (isMouseOverEntropyBar(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, entropyTooltip(menu.getEntropy(), menu.getEntropyMax()), mouseX, mouseY);
        } else if (isMouseOverShowChangesButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()), mouseX, mouseY);
        } else if (isMouseOverJumpButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronovault.jump"), mouseX, mouseY);
        } else if (isMouseOverDeleteBranchButton(mouseX, mouseY)) {
            Component label = TimelineProjectionManager.isSelectedBranchDeletable()
                    ? Component.translatable("gui.temporalindustries.chronovault.delete_branch")
                    : Component.translatable("gui.temporalindustries.chronovault.delete_branch_in_use");
            guiGraphics.renderTooltip(font, label, mouseX, mouseY);
        }
    }

    /** Shared with {@link ChronosphereScreen}'s identical entropy bar tooltip. */
    static Component entropyTooltip(int entropy, int max) {
        int offset = entropy - max / 2;
        String sign = offset >= 0 ? "+" : "";
        String display = sign + EntropyDisplay.formatBalance(offset);
        return Component.translatable("gui.temporalindustries.entropy.value", display);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOverAutoTrackTab(mouseX, mouseY)) {
            boolean newState = !menu.getBlockEntity().isAutoTrackingEnabled();
            PacketDistributor.sendToServer(new TimelineMachineToggleAutoTrackPacket(menu.getBlockPos(), newState));
            return true;
        }

        if (button == 0 && isMouseOverSettingsTab(mouseX, mouseY)) {
            settingsOverlayOpen = !settingsOverlayOpen;
            deleteHistoryConfirmPending = false;
            return true;
        }

        if (settingsOverlayOpen) {
            if (button == 0) {
                handleSettingsOverlayClick(mouseX, mouseY);
            }
            // Modal: swallow clicks so nothing underneath reacts to them.
            return true;
        }

        if (button == 0 && isMouseOverShowChangesButton(mouseX, mouseY)) {
            toggleShowChanges();
            return true;
        }

        if (button == 0 && isMouseOverJumpButton(mouseX, mouseY)) {
            jumpAndClose();
            return true;
        }

        if (button == 0 && isMouseOverDeleteBranchButton(mouseX, mouseY)) {
            deleteSelectedBranch();
            return true;
        }

        if (button == 0 && graphWidget.mouseClicked(mouseX, mouseY, panelX() + GRAPH_X_OFFSET, panelY() + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (settingsOverlayOpen) {
            return true;
        }
        if (graphWidget.mouseDragged(dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && graphWidget.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (settingsOverlayOpen) {
            return true;
        }
        if (graphWidget.mouseScrolled(mouseX, mouseY, scrollY, panelX() + GRAPH_X_OFFSET, panelY() + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Coordinates here must be absolute, since render() skips AbstractContainerScreen's translate.
        drawCenteredNoShadow(guiGraphics, Component.translatable("block.temporalindustries.chronovault"), panelX() + CONTENT_SIZE / 2, panelY() + 8, 0xFF3F3F3F);

        long now = TimelineProjectionManager.getCurrentGameTime();
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chronovault.preview_current", formatGameDayTime(now)), panelX() + 8, panelY() + PREVIEW_CURRENT_Y_OFFSET, TEXT_PRIMARY, false);

        if (TimelineProjectionManager.hasSelection()) {
            long selected = TimelineProjectionManager.getSelectedGameTime();
            long diff = selected - now;
            String direction = diff <= 0L
                    ? "gui.temporalindustries.chronovault.preview_past"
                    : "gui.temporalindustries.chronovault.preview_future";
            guiGraphics.drawString(font, Component.translatable(direction, formatSincePlaced(Math.abs(diff))), panelX() + 8, panelY() + PREVIEW_DIFF_Y_OFFSET, TEXT_PRIMARY, false);
        }
    }

    /** Formats an absolute game time as "Day {day} | {HH:MM}"; tick 0 within a day is 06:00. */
    private static String formatGameDayTime(long gameTime) {
        long day = Math.floorDiv(gameTime, 24000L) + 1L;
        long dayTicks = Math.floorMod(gameTime, 24000L);
        long hour = ((dayTicks / 1000L) + 6L) % 24L;
        long minute = (dayTicks % 1000L) * 60L / 1000L;
        return String.format("Day %d | %02d:%02d", day, hour, minute);
    }

    private static String formatSincePlaced(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
