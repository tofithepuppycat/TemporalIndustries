package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.client.window.ChronosphereMapView;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteActionPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteChunksPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import static io.github.tofithepuppycat.temporalindustries.client.screen.RemoteUi.*;

/**
 * Chunk selection of the Chrono Remote Control: the linked Chronosphere's claim map on a dark panel
 * with the selection count and controls beside it, and a toggle that outlines the selected chunks
 * in the world once the screen is closed.
 */
public class ChronoRemoteChunkScreen extends Screen {
    private static final int PADDING = 8;
    private static final int HEADER_HEIGHT = 26;
    private static final int FOOTER_HEIGHT = 32;
    private static final int SIDEBAR_WIDTH = 142;
    private static final int PREVIEW_BUTTON_WIDTH = 118;
    private static final int DONE_BUTTON_WIDTH = 64;
    private static final int BUTTON_GAP = 6;
    private static final int LINE_HEIGHT = 11;
    private static final int LEGEND_SWATCH = 7;

    private static final int COLOR_HOME = 0xFFFFD166;
    private static final int COLOR_SELECTED = 0xFF3D9BE0;
    private static final int COLOR_BLOCKED = 0xFFB55050;

    private final ChronosphereMapView mapView;

    private boolean opened = false;
    private boolean draggingMap = false;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public ChronoRemoteChunkScreen(BlockPos machinePos) {
        super(Component.translatable("gui.temporalindustries.chrono_remote.select_chunks"));
        mapView = new ChronosphereMapView(machinePos,
                () -> PacketDistributor.sendToServer(new ChronoRemoteActionPacket(ChronoRemoteActionPacket.Action.MAP)),
                toggles -> PacketDistributor.sendToServer(ChronoRemoteChunksPacket.fromPending(toggles)));
    }

    @Override
    protected void init() {
        panelWidth = 3 * PADDING + ChronosphereMapView.gridPixels() + SIDEBAR_WIDTH;
        panelHeight = HEADER_HEIGHT + ChronosphereMapView.gridPixels() + PADDING + FOOTER_HEIGHT;
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(0, (height - panelHeight) / 2);

        mapView.layout(mapLeft(), mapTop(), ChronosphereMapView.gridPixels());
        if (!opened) {
            opened = true;
            mapView.open();
        }
    }

    private int mapLeft() {
        return panelLeft + PADDING;
    }

    private int mapTop() {
        return panelTop + HEADER_HEIGHT;
    }

    private int sidebarLeft() {
        return mapLeft() + ChronosphereMapView.gridPixels() + PADDING;
    }

    private int sidebarWidth() {
        return panelLeft + panelWidth - PADDING - sidebarLeft();
    }

    private int footerTop() {
        return panelTop + panelHeight - FOOTER_HEIGHT;
    }

    private int doneButtonLeft() {
        return panelLeft + panelWidth - PADDING - DONE_BUTTON_WIDTH;
    }

    private int previewButtonLeft() {
        return doneButtonLeft() - BUTTON_GAP - PREVIEW_BUTTON_WIDTH;
    }

    private int buttonTop() {
        return footerTop() + (FOOTER_HEIGHT - BUTTON_HEIGHT) / 2;
    }

    private static Component previewLabel() {
        return Component.translatable("gui.temporalindustries.chrono_remote.preview_chunks",
                Component.translatable(ChronoRemoteClientState.isChunkPreview()
                        ? "gui.temporalindustries.chrono_remote.on"
                        : "gui.temporalindustries.chrono_remote.off"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        mapView.tick();
    }

    @Override
    public void removed() {
        // Closing sends the claims and releases the map still holds.
        mapView.close();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, BACKDROP_COLOR);
        drawPanel(guiGraphics, panelLeft, panelTop, panelWidth, panelHeight, PANEL_COLOR, PANEL_BORDER_COLOR);

        guiGraphics.drawString(font, title, panelLeft + PADDING + 2, panelTop + (HEADER_HEIGHT - font.lineHeight) / 2, ACCENT_COLOR);

        mapView.layout(mapLeft(), mapTop(), ChronosphereMapView.gridPixels());
        mapView.render(guiGraphics, mouseX, mouseY);
        renderSidebar(guiGraphics);
        renderFooter(guiGraphics, mouseX, mouseY);
    }

    private void renderSidebar(GuiGraphics guiGraphics) {
        int left = sidebarLeft();
        int top = mapTop();
        int sidebarWidth = sidebarWidth();
        drawPanel(guiGraphics, left, top, sidebarWidth, ChronosphereMapView.gridPixels(), SIDEBAR_COLOR, DIVIDER_COLOR);

        int x = left + PADDING;
        int y = top + PADDING;
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chrono_remote.chunks.selected"), x, y, TEXT_MUTED);
        y += LINE_HEIGHT;
        guiGraphics.drawString(font, mapView.selectedCount() + "/" + ChronosphereMapView.totalClaimable(), x, y, ACCENT_COLOR);
        y += LINE_HEIGHT + 6;

        y = legend(guiGraphics, x, y, COLOR_HOME, "home");
        y = legend(guiGraphics, x, y, COLOR_SELECTED, "selected");
        y = legend(guiGraphics, x, y, COLOR_BLOCKED, "blocked");

        y += 6;
        guiGraphics.drawWordWrap(font, Component.translatable("gui.temporalindustries.chrono_remote.chunks.controls"),
                x, y, sidebarWidth - 2 * PADDING, TEXT_MUTED);
    }

    private int legend(GuiGraphics guiGraphics, int x, int y, int color, String key) {
        guiGraphics.fill(x, y + 1, x + LEGEND_SWATCH, y + 1 + LEGEND_SWATCH, color);
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chrono_remote.chunks.legend." + key),
                x + LEGEND_SWATCH + 4, y, TEXT_SECONDARY);
        return y + LINE_HEIGHT;
    }

    private void renderFooter(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(panelLeft + 1, footerTop(), panelLeft + panelWidth - 1, footerTop() + 1, DIVIDER_COLOR);

        drawButton(guiGraphics, font, previewButtonLeft(), buttonTop(), PREVIEW_BUTTON_WIDTH, previewLabel(), true, false, mouseX, mouseY);
        drawButton(guiGraphics, font, doneButtonLeft(), buttonTop(), DONE_BUTTON_WIDTH,
                Component.translatable("gui.temporalindustries.chrono_remote.done"), true, true, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (isOver(mouseX, mouseY, previewButtonLeft(), buttonTop(), PREVIEW_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                ChronoRemoteClientState.setChunkPreview(!ChronoRemoteClientState.isChunkPreview());
                return true;
            }
            if (isOver(mouseX, mouseY, doneButtonLeft(), buttonTop(), DONE_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                onClose();
                return true;
            }
        }
        if (mapView.isOverGrid(mouseX, mouseY)) {
            draggingMap = true;
            mapView.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingMap) {
            mapView.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingMap) {
            draggingMap = false;
            mapView.mouseReleased(mouseX, mouseY, button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mapView.isOverGrid(mouseX, mouseY)) {
            mapView.mouseScrolled(mouseX, mouseY, scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
