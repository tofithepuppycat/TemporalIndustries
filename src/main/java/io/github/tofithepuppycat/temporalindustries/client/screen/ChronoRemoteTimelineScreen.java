package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.client.ShortEnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Commit picker of the Chrono Remote Control's jump option: the linked machine's timeline on a
 * dark panel with the selected commit's details beside it. Choosing a commit closes the screen and
 * starts the in-world preview, where the remote then offers to jump or cancel.
 */
public class ChronoRemoteTimelineScreen extends Screen {
    private static final int MAX_WIDTH = 560;
    private static final int MAX_HEIGHT = 320;
    private static final int MARGIN = 16;
    private static final int PADDING = 8;
    private static final int HEADER_HEIGHT = 26;
    private static final int FOOTER_HEIGHT = 32;
    private static final int SIDEBAR_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 18;
    private static final int PRIMARY_BUTTON_WIDTH = 112;
    private static final int SECONDARY_BUTTON_WIDTH = 64;
    private static final int LINE_HEIGHT = 11;

    private static final int SYNC_INTERVAL_TICKS = 20;

    private static final int BACKDROP_COLOR = 0x90000000;
    private static final int PANEL_COLOR = 0xF2141420;
    private static final int PANEL_BORDER_COLOR = 0xFF3A3A55;
    private static final int SIDEBAR_COLOR = 0xFF1B1B2B;
    private static final int DIVIDER_COLOR = 0xFF2C2C42;
    private static final int TEXT_PRIMARY = 0xFFEDEDF5;
    private static final int TEXT_SECONDARY = 0xFFA8A8BC;
    private static final int TEXT_MUTED = 0xFF70708A;
    private static final int ACCENT_COLOR = 0xFF8FD8FF;
    private static final int COST_COLOR = 0xFFFFD166;
    private static final int PRIMARY_COLOR = 0xFF3F7F5A;
    private static final int PRIMARY_HOVER_COLOR = 0xFF52A074;
    private static final int SECONDARY_COLOR = 0xFF2C2C42;
    private static final int SECONDARY_HOVER_COLOR = 0xFF3C3C5A;
    private static final int DISABLED_COLOR = 0xFF26263A;

    private final BlockPos machinePos;
    private final TimelineGraphWidget graphWidget = new TimelineGraphWidget();

    private boolean started = false;
    private boolean previewStarted = false;
    private int ticksSinceSync = 0;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public ChronoRemoteTimelineScreen(BlockPos machinePos) {
        super(Component.translatable("gui.temporalindustries.chrono_remote.timeline.title"));
        this.machinePos = machinePos;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(MAX_WIDTH, width - 2 * MARGIN);
        panelHeight = Math.min(MAX_HEIGHT, height - 2 * MARGIN);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;

        TimelineProjectionManager.setActiveMachine(machinePos);
        graphWidget.init(machinePos);
        if (!started) {
            started = true;
            TimelineProjectionManager.clearAll();
            TimelineProjectionManager.setActiveMachine(machinePos);
            requestView(true);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        graphWidget.onClose();
        if (!previewStarted) {
            TimelineProjectionManager.clearAll();
        }
    }

    @Override
    public void tick() {
        if (minecraft != null && minecraft.level != null) {
            TimelineProjectionManager.setCurrentGameTime(minecraft.level.getGameTime());
        }
        if (++ticksSinceSync >= SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            requestView(false);
        }
    }

    /** Asks for the commit graph plus the cost of the selection; forceFull bypasses the server's "nothing changed" skip. */
    private void requestView(boolean forceFull) {
        long head = forceFull ? Long.MIN_VALUE : TimelineProjectionManager.getHeadCommitId();
        long version = forceFull ? Long.MIN_VALUE : TimelineProjectionManager.getPreviewVersion();
        PacketDistributor.sendToServer(new TimelinePreviewRequestPacket(
                machinePos, head, version, TimelineProjectionManager.getSelectedCommitId()));
    }

    private int graphLeft() {
        return panelLeft + PADDING;
    }

    private int graphTop() {
        return panelTop + HEADER_HEIGHT;
    }

    private int graphWidth() {
        return panelWidth - SIDEBAR_WIDTH - 2 * PADDING;
    }

    private int graphHeight() {
        return panelHeight - HEADER_HEIGHT - FOOTER_HEIGHT;
    }

    private int sidebarLeft() {
        return graphLeft() + graphWidth() + PADDING;
    }

    private int footerTop() {
        return panelTop + panelHeight - FOOTER_HEIGHT;
    }

    private int previewButtonLeft() {
        return panelLeft + panelWidth - PADDING - PRIMARY_BUTTON_WIDTH;
    }

    private int closeButtonLeft() {
        return previewButtonLeft() - 6 - SECONDARY_BUTTON_WIDTH;
    }

    private int buttonTop() {
        return footerTop() + (FOOTER_HEIGHT - BUTTON_HEIGHT) / 2;
    }

    /** Whether a commit other than the one the world already reflects is selected. */
    private boolean canPreview() {
        return TimelineProjectionManager.hasSelection()
                && TimelineProjectionManager.getSelectedCommitId() != TimelineProjectionManager.getHeadCommitId();
    }

    private void startPreview() {
        if (!canPreview() || minecraft == null) return;
        previewStarted = true;
        ChronoRemoteClientState.startPreview(TimelineProjectionManager.getSelectedCommitId());
        minecraft.setScreen(null);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, BACKDROP_COLOR);
        drawPanel(guiGraphics, panelLeft, panelTop, panelWidth, panelHeight, PANEL_COLOR, PANEL_BORDER_COLOR);

        renderHeader(guiGraphics);
        graphWidget.render(guiGraphics, font, graphLeft(), graphTop(), graphWidth(), graphHeight());
        if (TimelineProjectionManager.getCommits().isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.translatable("gui.temporalindustries.chrono_remote.timeline.loading"),
                    graphLeft() + graphWidth() / 2, graphTop() + graphHeight() / 2 - font.lineHeight / 2, TEXT_MUTED);
        }
        renderSidebar(guiGraphics);
        renderFooter(guiGraphics, mouseX, mouseY);

        List<net.minecraft.util.FormattedCharSequence> tooltip = graphWidget.getTooltipAt(mouseX, mouseY);
        if (!tooltip.isEmpty()) {
            guiGraphics.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private void renderHeader(GuiGraphics guiGraphics) {
        int textY = panelTop + (HEADER_HEIGHT - font.lineHeight) / 2;
        guiGraphics.drawString(font, title, panelLeft + PADDING + 2, textY, ACCENT_COLOR);

        String branch = TimelineProjectionManager.getCurrentBranch();
        Component now = Component.translatable("gui.temporalindustries.chrono_remote.timeline.now",
                formatGameDayTime(TimelineProjectionManager.getCurrentGameTime()));
        Component right = branch.isEmpty() ? now : Component.literal(branch + "  ·  ").append(now);
        guiGraphics.drawString(font, right, panelLeft + panelWidth - PADDING - 2 - font.width(right), textY, TEXT_SECONDARY);
    }

    private void renderSidebar(GuiGraphics guiGraphics) {
        int left = sidebarLeft();
        int top = graphTop();
        int sidebarWidth = SIDEBAR_WIDTH - PADDING;
        drawPanel(guiGraphics, left, top, sidebarWidth, graphHeight(), SIDEBAR_COLOR, DIVIDER_COLOR);

        int x = left + PADDING;
        int y = top + PADDING;
        CommitNode selected = selectedCommit();
        if (selected == null) {
            guiGraphics.drawWordWrap(font, Component.translatable("gui.temporalindustries.chrono_remote.timeline.select_hint"),
                    x, y, sidebarWidth - 2 * PADDING, TEXT_MUTED);
            return;
        }

        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chrono_remote.timeline.commit"), x, y, TEXT_MUTED);
        y += LINE_HEIGHT;
        guiGraphics.drawString(font, selected.shortHash(), x, y, ACCENT_COLOR);
        y += LINE_HEIGHT + 4;

        long now = TimelineProjectionManager.getCurrentGameTime();
        y = detail(guiGraphics, x, y, "recorded", formatGameDayTime(selected.gameTime()));
        y = detail(guiGraphics, x, y, "age", formatAge(now - selected.gameTime()));
        y = detail(guiGraphics, x, y, "chunks", String.valueOf(selected.chunkCount()));
        if (selected.blockChangeCount() > 0) {
            y = detail(guiGraphics, x, y, "changes", String.valueOf(selected.blockChangeCount()));
        }

        y += 4;
        if (selected.id() == TimelineProjectionManager.getHeadCommitId()) {
            guiGraphics.drawWordWrap(font, Component.translatable("gui.temporalindustries.chrono_remote.timeline.is_current"),
                    x, y, sidebarWidth - 2 * PADDING, TEXT_SECONDARY);
            return;
        }
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chrono_remote.timeline.cost"), x, y, TEXT_MUTED);
        y += LINE_HEIGHT;
        String cost = TimelineProjectionManager.getJumpCost(selected.id())
                .map(c -> ShortEnergyDisplay.format(c.total())).orElse("...");
        guiGraphics.drawString(font, cost, x, y, COST_COLOR);
    }

    /** A small muted label with its value beneath; returns the y below it. */
    private int detail(GuiGraphics guiGraphics, int x, int y, String key, String value) {
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chrono_remote.timeline." + key), x, y, TEXT_MUTED);
        y += LINE_HEIGHT;
        guiGraphics.drawString(font, value, x, y, TEXT_PRIMARY);
        return y + LINE_HEIGHT + 3;
    }

    private void renderFooter(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(panelLeft + 1, footerTop(), panelLeft + panelWidth - 1, footerTop() + 1, DIVIDER_COLOR);

        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chrono_remote.timeline.controls"),
                panelLeft + PADDING + 2, footerTop() + (FOOTER_HEIGHT - font.lineHeight) / 2, TEXT_MUTED);

        drawButton(guiGraphics, closeButtonLeft(), buttonTop(), SECONDARY_BUTTON_WIDTH,
                Component.translatable("gui.temporalindustries.chrono_remote.close"), true, false, mouseX, mouseY);
        drawButton(guiGraphics, previewButtonLeft(), buttonTop(), PRIMARY_BUTTON_WIDTH,
                Component.translatable("gui.temporalindustries.chrono_remote.timeline.preview"), canPreview(), true, mouseX, mouseY);
    }

    private void drawButton(GuiGraphics guiGraphics, int x, int y, int buttonWidth, Component label, boolean enabled,
                            boolean primary, int mouseX, int mouseY) {
        boolean hovered = enabled && isOver(mouseX, mouseY, x, y, buttonWidth, BUTTON_HEIGHT);
        int color = !enabled ? DISABLED_COLOR
                : primary ? (hovered ? PRIMARY_HOVER_COLOR : PRIMARY_COLOR)
                : (hovered ? SECONDARY_HOVER_COLOR : SECONDARY_COLOR);
        drawPanel(guiGraphics, x, y, buttonWidth, BUTTON_HEIGHT, color, color);
        guiGraphics.drawCenteredString(font, label, x + buttonWidth / 2, y + (BUTTON_HEIGHT - font.lineHeight) / 2 + 1,
                enabled ? TEXT_PRIMARY : TEXT_MUTED);
    }

    /** A filled rectangle with notched corners and a 1px border. */
    private static void drawPanel(GuiGraphics guiGraphics, int x, int y, int w, int h, int fill, int border) {
        guiGraphics.fill(x + 1, y, x + w - 1, y + h, border);
        guiGraphics.fill(x, y + 1, x + w, y + h - 1, border);
        guiGraphics.fill(x + 2, y + 1, x + w - 2, y + h - 1, fill);
        guiGraphics.fill(x + 1, y + 2, x + w - 1, y + h - 2, fill);
    }

    private static boolean isOver(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private CommitNode selectedCommit() {
        long id = TimelineProjectionManager.getSelectedCommitId();
        for (CommitNode commit : TimelineProjectionManager.getCommits()) {
            if (commit.id() == id) return commit;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (isOver(mouseX, mouseY, previewButtonLeft(), buttonTop(), PRIMARY_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                startPreview();
                return true;
            }
            if (isOver(mouseX, mouseY, closeButtonLeft(), buttonTop(), SECONDARY_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                onClose();
                return true;
            }
            if (graphWidget.mouseClicked(mouseX, mouseY, graphLeft(), graphTop(), graphWidth(), graphHeight())) {
                // The cost and ghost preview are resolved server-side per selected commit, so ask
                // right away instead of waiting for the next poll.
                requestView(false);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return graphWidget.mouseDragged(dragX, dragY) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && graphWidget.mouseReleased())
                || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return graphWidget.mouseScrolled(mouseX, mouseY, scrollY, graphLeft(), graphTop(), graphWidth(), graphHeight())
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            startPreview();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Formats an absolute game time as "Day {day} | {HH:MM}"; tick 0 within a day is 06:00. */
    private static String formatGameDayTime(long gameTime) {
        long day = Math.floorDiv(gameTime, 24000L) + 1L;
        long dayTicks = Math.floorMod(gameTime, 24000L);
        long hour = ((dayTicks / 1000L) + 6L) % 24L;
        long minute = (dayTicks % 1000L) * 60L / 1000L;
        return String.format("Day %d | %02d:%02d", day, hour, minute);
    }

    private static String formatAge(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) return hours + "h " + minutes + "m ago";
        if (minutes > 0L) return minutes + "m " + seconds + "s ago";
        return seconds + "s ago";
    }
}
