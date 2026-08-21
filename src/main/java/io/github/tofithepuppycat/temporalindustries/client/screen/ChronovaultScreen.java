package io.github.tofithepuppycat.temporalindustries.client.screen;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.mojang.blaze3d.systems.RenderSystem;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.IconTabRenderer;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.ChronovaultMenu;
import io.github.tofithepuppycat.temporalindustries.network.RollbackChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineDeleteHistoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineToggleAutoTrackPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
    private static final int ICON_CONFIG_SIZE = 26;
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;
    /** base.png's actual panel artwork sits inset this many pixels on every side within the
     * 256x256 canvas — every layout constant below is measured from the artwork's edge (via
     * {@link #panelX()}/{@link #panelY()}), not from leftPos/topPos directly, and CONTENT_SIZE
     * (not imageWidth) is the usable width/height for centering and right-edge anchoring. */
    private static final int CONTENT_MARGIN = 16;
    private static final int CONTENT_SIZE = TEXTURE_WIDTH - 2 * CONTENT_MARGIN;

    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFC0C0C0;
    private static final int COLOR_BORDER = 0xFF000000;

    // Auto-track/settings tabs: a vertical stack mostly overlapping the panel's right edge,
    // protruding outward — mirrors ChronosphereScreen's (including drawing them BEFORE the panel
    // background in render() so the panel's edge tucks them in) minus its bookmark/claim map tab,
    // since a Chronovault only ever has its own one chunk, never a claimed range.
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
    /** Below the graph: a small gap, the two preview-time labels, another gap, then the button
     * row — see PREVIEW_CURRENT_Y_OFFSET/PREVIEW_DIFF_Y_OFFSET/BUTTON_ROW_Y_OFFSET below, all of
     * which this height is sized to leave room for within CONTENT_SIZE. */
    private static final int GRAPH_HEIGHT = 154;

    private static final int ENERGY_BAR_X_OFFSET = 140;
    private static final int ENERGY_BAR_Y_OFFSET = 7;
    private static final int ENERGY_BAR_WIDTH = 77;
    private static final int ENERGY_BAR_HEIGHT = 8;

    private static final int ENTROPY_BAR_X_OFFSET = 79;
    private static final int ENTROPY_BAR_Y_OFFSET = 7;
    private static final int ENTROPY_BAR_WIDTH = 52;
    private static final int ENTROPY_BAR_HEIGHT = 8;
    private static final int COLOR_ORDER = 0xFF000000 | EntropyType.ORDER.color();
    private static final int COLOR_CHAOS = 0xFF000000 | EntropyType.CHAOS.color();

    private static final int PREVIEW_CURRENT_Y_OFFSET = 176;
    private static final int PREVIEW_DIFF_Y_OFFSET = 186;
    private static final int BUTTON_ROW_Y_OFFSET = 200;

    private static final int SYNC_INTERVAL_TICKS = 20;

    private final TimelineGraphWidget graphWidget = new TimelineGraphWidget();

    private Button jumpButton;
    private Button showChangesButton;
    private int ticksSinceSync = 0;
    private boolean settingsOverlayOpen = false;
    /** Whether the settings overlay is showing the "are you sure" step rather than the plain
     * Delete All History button — reset whenever the overlay itself closes. */
    private boolean deleteHistoryConfirmPending = false;

    private int autoTrackX;
    private int autoTrackY;
    private int settingsX;
    private int settingsY;

    public ChronovaultScreen(ChronovaultMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = TEXTURE_WIDTH;
        imageHeight = TEXTURE_HEIGHT;
        inventoryLabelY = imageHeight + 100; // Push off-screen to hide inventory
    }

    /** The visible top-left corner of base.png's artwork — every layout offset in this screen is
     * measured from here, not from leftPos/topPos, which are the outer edge of the 16px-inset
     * canvas the artwork sits within. */
    private int panelX() {
        return leftPos + CONTENT_MARGIN;
    }

    private int panelY() {
        return topPos + CONTENT_MARGIN;
    }

    @Override
    protected void init() {
        super.init();

        int buttonY = panelY() + BUTTON_ROW_Y_OFFSET;
        int buttonWidth = 76;
        int gap = 3;
        int groupX = panelX() + (CONTENT_SIZE - (buttonWidth * 2 + gap)) / 2;

        showChangesButton = Button.builder(showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()), btn -> toggleShowChanges())
            .pos(groupX, buttonY)
            .size(buttonWidth, 20)
            .build();
        addRenderableWidget(showChangesButton);

        jumpButton = Button.builder(Component.translatable("gui.temporalindustries.chronovault.jump"), btn -> jumpAndClose())
            .pos(groupX + buttonWidth + gap, buttonY)
            .size(buttonWidth, 20)
            .build();
        jumpButton.active = TimelineProjectionManager.hasSelection();
        addRenderableWidget(jumpButton);

        autoTrackX = panelX() + CONTENT_SIZE - TAB_OVERLAP;
        autoTrackY = panelY() + AUTO_TRACK_Y_OFFSET;
        settingsX = panelX() + CONTENT_SIZE - TAB_OVERLAP;
        settingsY = panelY() + SETTINGS_Y_OFFSET;

        TimelineProjectionManager.setActiveMachine(menu.getBlockPos());
        graphWidget.init(menu.getBlockPos());
        // Force a full response regardless of whatever TimelineProjectionManager still has
        // cached from a previously viewed machine (Long.MIN_VALUE can never equal a real head id).
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

        // Periodically re-fetch commits so new ones show up while the GUI is open. The server
        // skips the (potentially large, ever-growing) full commit list reply entirely when the
        // chunk's head hasn't moved since our last known value, so this is cheap on the common
        // case of nothing having changed in the last second.
        ticksSinceSync++;
        if (ticksSinceSync >= SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            PacketDistributor.sendToServer(
                    new TimelinePreviewRequestPacket(menu.getBlockPos(), TimelineProjectionManager.getHeadCommitId(),
                            TimelineProjectionManager.getPreviewVersion()));
        }

        jumpButton.active = TimelineProjectionManager.hasSelection();
        showChangesButton.visible = !settingsOverlayOpen;
        jumpButton.visible = !settingsOverlayOpen;
    }

    private void toggleShowChanges() {
        TimelineProjectionManager.toggleShowChanges();
        showChangesButton.setMessage(showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()));
    }

    /** Mirrors the button rects renderSettingsOverlay draws, since they're plain fills rather than
     * Button widgets (consistent with ChronosphereScreen's identical settings overlay). */
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
            guiGraphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + ENERGY_BAR_HEIGHT - 1, 0xFF4DD0E1);
        }

        renderEntropyBar(guiGraphics);
    }

    private boolean isMouseOverEnergyBar(int mouseX, int mouseY) {
        int barX = panelX() + ENERGY_BAR_X_OFFSET;
        int barY = panelY() + ENERGY_BAR_Y_OFFSET;
        return mouseX >= barX && mouseX <= barX + ENERGY_BAR_WIDTH && mouseY >= barY && mouseY <= barY + ENERGY_BAR_HEIGHT;
    }

    /** Bidirectional order↔chaos balance bar: fills from the center tick outward, white toward
     * order (below the midpoint) and dark purple toward chaos (above it). */
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

        // Placeholder icon: a small filled "record" dot, echoing a recording indicator — same as
        // ChronosphereScreen's auto-track tab.
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
                    panelX() + CONTENT_SIZE / 2, buttonY - 14, 0xFFFF6B6B);

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

        // Normally the tabs are drawn BEFORE the panel background, so the panel's opaque texture
        // paints over their small edge overlap and tucks them in, same as a vanilla recipe-book
        // tab. While the settings overlay is open it's the only way to close it, so it's drawn on
        // top of everything instead (below, after the overlay), same as ChronosphereScreen.
        if (!settingsOverlayOpen) {
            renderTabs(guiGraphics, mouseX, mouseY);
        }

        // Skip super.render() to avoid rendering inventory slots
        renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderLabels(guiGraphics, mouseX, mouseY);
        for (var widget : renderables) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (settingsOverlayOpen) {
            renderSettingsOverlay(guiGraphics, mouseX, mouseY);
            renderTabs(guiGraphics, mouseX, mouseY);
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
            Component tooltipComponent = Component.literal(menu.getEnergyStored() + " / " + menu.getEnergyCapacity() + " FE");
            guiGraphics.renderTooltip(font, tooltipComponent, mouseX, mouseY);
        } else if (isMouseOverEntropyBar(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, entropyTooltip(menu.getEntropy(), menu.getEntropyMax()), mouseX, mouseY);
        }
    }

    /** Shared with {@link ChronosphereScreen}'s identical entropy bar tooltip. */
    static Component entropyTooltip(int entropy, int max) {
        int half = max / 2;
        String displayEntropy = EntropyDisplay.format(entropy);
        String displayMax = EntropyDisplay.format(max);
        if (entropy == half) {
            return Component.translatable("gui.temporalindustries.entropy.balanced", displayEntropy, displayMax);
        }
        String key = entropy > half ? "gui.temporalindustries.entropy.chaos" : "gui.temporalindustries.entropy.order";
        return Component.translatable(key, displayEntropy, displayMax);
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
            // Modal: swallow every click on the panel while it's open so nothing underneath
            // (the graph, the buttons) reacts to it.
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
        // Skip super.renderLabels() to avoid rendering inventory slot labels.
        // Our render() override skips AbstractContainerScreen's leftPos/topPos translate,
        // so coordinates here must be absolute (unlike vanilla renderLabels overrides).
        drawCenteredNoShadow(guiGraphics, Component.translatable("block.temporalindustries.chronovault"), panelX() + CONTENT_SIZE / 2, panelY() + 8, 0xFF3F3F3F);

        long now = TimelineProjectionManager.getCurrentGameTime();
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chronovault.preview_current", formatGameDayTime(now)), panelX() + 8, panelY() + PREVIEW_CURRENT_Y_OFFSET, 0xFFFFFF, false);

        if (TimelineProjectionManager.hasSelection()) {
            long selected = TimelineProjectionManager.getSelectedGameTime();
            long diff = selected - now;
            String direction = diff <= 0L
                    ? "gui.temporalindustries.chronovault.preview_past"
                    : "gui.temporalindustries.chronovault.preview_future";
            guiGraphics.drawString(font, Component.translatable(direction, formatSincePlaced(Math.abs(diff))), panelX() + 8, panelY() + PREVIEW_DIFF_Y_OFFSET, 0xFFFFFF, false);
        }
    }

    /** Formats an absolute game time as "Day {day} | {HH:MM}". Day 1 starts at game time 0; a
     * Minecraft day is 24000 ticks, and tick 0 within a day is 06:00. */
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
