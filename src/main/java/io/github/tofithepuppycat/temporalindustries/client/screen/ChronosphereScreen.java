package io.github.tofithepuppycat.temporalindustries.client.screen;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChronoMapSampler;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereMapClientState;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereDeleteHistoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereMapRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereStateRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereToggleAutoTrackPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereToggleChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.RollbackChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/** GUI for the Chronosphere block: the same timeline graph view as the Time Machine (via
 * {@link TimelineGraphWidget}), reading/jumping the home chunk's history exactly like a Time
 * Machine — except Jump moves every chunk this Chronosphere has claimed, not just the one shown.
 * The 5x5 claim map lives behind a square bookmark tab on the panel's side and opens as an
 * overlay, rather than occupying the main view. */
@SuppressWarnings("null")
public class ChronosphereScreen extends AbstractContainerScreen<ChronosphereMenu> {
    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_HEIGHT = 256;

    private static final int GRAPH_X_OFFSET = 8;
    private static final int GRAPH_Y_OFFSET = 18;
    private static final int GRAPH_WIDTH = 240;
    private static final int GRAPH_HEIGHT = 178;

    private static final int ENERGY_BAR_X_OFFSET = 160;
    private static final int ENERGY_BAR_Y_OFFSET = 7;
    private static final int ENERGY_BAR_WIDTH = 88;
    private static final int ENERGY_BAR_HEIGHT = 8;

    private static final int SYNC_INTERVAL_TICKS = 20;
    /** How often the map overlay re-fetches terrain thumbnails while open, so it doesn't go stale
     * for a player who leaves it open and keeps building nearby. */
    private static final int MAP_SYNC_INTERVAL_TICKS = 100;

    // Chunk timeline tabs: a thin strip along the top of the graph — "All" (the shared/merged view
    // across every claimed chunk) plus one small tab per claimed chunk, so the graph can show
    // either the whole picture or drill into a single chunk's own history.
    private static final int TAB_STRIP_HEIGHT = 11;
    private static final int TAB_GAP = 1;
    private static final int TAB_MIN_WIDTH = 8;
    private static final int TAB_MAX_WIDTH = 26;

    // Bookmark tab: mostly overlaps the panel's right edge, protruding outward, like a vanilla
    // recipe-book tab attached to a crafting GUI.
    private static final int BOOKMARK_SIZE = 28;
    private static final int BOOKMARK_OVERLAP = 6;
    private static final int BOOKMARK_Y_OFFSET = 40;

    // Auto-track tab: same protruding style, stacked directly below the bookmark tab. Unlike the
    // bookmark tab it isn't a modal overlay toggle — clicking it just flips auto-tracking on/off.
    private static final int AUTO_TRACK_GAP = 4;
    private static final int AUTO_TRACK_Y_OFFSET = BOOKMARK_Y_OFFSET + BOOKMARK_SIZE + AUTO_TRACK_GAP;

    // Settings tab: same protruding style again, stacked below the auto-track tab. A modal overlay
    // toggle like the bookmark tab, just with a destructive action behind it instead of the claim map.
    private static final int SETTINGS_GAP = 4;
    private static final int SETTINGS_Y_OFFSET = AUTO_TRACK_Y_OFFSET + BOOKMARK_SIZE + SETTINGS_GAP;
    private static final int DELETE_BUTTON_WIDTH = 140;
    private static final int DELETE_BUTTON_HEIGHT = 20;
    private static final int CONFIRM_BUTTON_WIDTH = 66;

    private static final int RADIUS = ChronosphereBlockEntity.MAX_RADIUS;
    private static final int GRID_SIZE = RADIUS * 2 + 1;
    private static final int CELL_SIZE = 32;
    private static final int CELL_GAP = 3;
    private static final int GRID_PIXELS = GRID_SIZE * CELL_SIZE + (GRID_SIZE - 1) * CELL_GAP;
    private static final int TOTAL_CLAIMABLE = countClaimableCells();

    private static final int COLOR_HOME       = 0xFFFFD166;
    private static final int COLOR_SELECTED   = 0xFF66C7FF;
    private static final int COLOR_BLOCKED    = 0xFF8A3A3A;
    private static final int COLOR_AVAILABLE  = 0xFF3A3A3A;
    private static final int COLOR_BORDER     = 0xFF000000;

    private final TimelineGraphWidget graphWidget = new TimelineGraphWidget();

    private Button jumpButton;
    private Button showChangesButton;
    private int ticksSinceSync = 0;
    private int ticksSinceMapSync = 0;
    private boolean mapOverlayOpen = false;
    private boolean settingsOverlayOpen = false;
    /** Whether the settings overlay is showing the "are you sure" step rather than the plain
     * Delete All History button — reset whenever the overlay itself closes. */
    private boolean deleteHistoryConfirmPending = false;
    /** null = the shared "All" view; otherwise the packed key of one claimed chunk's own tab. */
    @Nullable
    private Long selectedViewChunkKey = null;

    private int bookmarkX;
    private int bookmarkY;
    private int autoTrackX;
    private int autoTrackY;
    private int settingsX;
    private int settingsY;
    private int gridX;
    private int gridY;

    public ChronosphereScreen(ChronosphereMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight + 100; // Push off-screen to hide inventory
    }

    private static int countClaimableCells() {
        int count = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (ChronosphereBlockEntity.isWithinRadius(dx, dz)) count++;
            }
        }
        return count;
    }

    @Override
    protected void init() {
        super.init();

        bookmarkX = leftPos + imageWidth - BOOKMARK_OVERLAP;
        bookmarkY = topPos + BOOKMARK_Y_OFFSET;
        autoTrackX = leftPos + imageWidth - BOOKMARK_OVERLAP;
        autoTrackY = topPos + AUTO_TRACK_Y_OFFSET;
        settingsX = leftPos + imageWidth - BOOKMARK_OVERLAP;
        settingsY = topPos + SETTINGS_Y_OFFSET;
        gridX = leftPos + (imageWidth - GRID_PIXELS) / 2;
        gridY = topPos + 44;

        int buttonY = topPos + 224;
        int buttonWidth = 76;
        int gap = 3;
        int groupX = leftPos + (imageWidth - (buttonWidth * 2 + gap)) / 2;

        showChangesButton = Button.builder(showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()), btn -> toggleShowChanges())
                .pos(groupX, buttonY)
                .size(buttonWidth, 20)
                .build();
        addRenderableWidget(showChangesButton);

        jumpButton = Button.builder(Component.translatable("gui.temporalindustries.time_machine.jump"), btn -> jumpAndClose())
                .pos(groupX + buttonWidth + gap, buttonY)
                .size(buttonWidth, 20)
                .build();
        jumpButton.active = TimelineProjectionManager.hasSelection();
        addRenderableWidget(jumpButton);

        TimelineProjectionManager.setActiveMachine(menu.getBlockPos());
        graphWidget.init(menu.getBlockPos());
        ChronosphereClientState.setActiveMachine(menu.getBlockPos());
        selectedViewChunkKey = null;
        TimelineProjectionManager.setSelectedViewChunk(null);

        requestTimelineView(true);
        PacketDistributor.sendToServer(new ChronosphereStateRequestPacket(menu.getBlockPos()));
    }

    /** Requests the currently selected tab's commit graph — the shared "All" view when
     * selectedViewChunkKey is null, or that one chunk's own view otherwise. forceFull bypasses the
     * "nothing changed since lastKnownHeadCommitId" skip on the server, which matters right after
     * switching tabs: the previous tab's cached head id could otherwise coincidentally match the
     * new tab's and cause the server to (wrongly) skip replying with its actual data. */
    private void requestTimelineView(boolean forceFull) {
        ChunkPos viewChunk = selectedViewChunkKey == null ? null : new ChunkPos(selectedViewChunkKey);
        long lastKnown = forceFull ? Long.MIN_VALUE : TimelineProjectionManager.getHeadCommitId();
        PacketDistributor.sendToServer(new TimelinePreviewRequestPacket(menu.getBlockPos(), lastKnown, viewChunk));
    }

    /** Every claimed chunk's packed key, home chunk first then ascending — a stable order for the
     * tab strip regardless of the (unordered) set ChronosphereClientState syncs. */
    private List<Long> getOrderedTabChunkKeys() {
        long homeKey = new ChunkPos(menu.getBlockPos()).toLong();
        List<Long> keys = new ArrayList<>(ChronosphereClientState.getSelectedChunks());
        keys.sort((a, b) -> {
            if (a.equals(b)) return 0;
            if (a == homeKey) return -1;
            if (b == homeKey) return 1;
            return Long.compare(a, b);
        });
        return keys;
    }

    @Override
    public void onClose() {
        graphWidget.onClose();
        ChronosphereMapClientState.clearAll();
        super.onClose();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        TimelineProjectionManager.setCurrentGameTime(getCurrentGameTime());

        ticksSinceSync++;
        if (ticksSinceSync >= SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            requestTimelineView(false);
            PacketDistributor.sendToServer(new ChronosphereStateRequestPacket(menu.getBlockPos()));
        }

        jumpButton.active = TimelineProjectionManager.hasSelection();
        boolean overlayOpen = mapOverlayOpen || settingsOverlayOpen;
        showChangesButton.visible = !overlayOpen;
        jumpButton.visible = !overlayOpen;

        if (mapOverlayOpen) {
            ticksSinceMapSync++;
            if (ticksSinceMapSync >= MAP_SYNC_INTERVAL_TICKS) {
                ticksSinceMapSync = 0;
                PacketDistributor.sendToServer(new ChronosphereMapRequestPacket(menu.getBlockPos()));
            }
        }
    }

    private void toggleShowChanges() {
        TimelineProjectionManager.toggleShowChanges();
        showChangesButton.setMessage(showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()));
    }

    /** Mirrors the button rects renderSettingsOverlay draws, since they're plain fills rather than
     * Button widgets (consistent with the claim map overlay's own click handling). */
    private void handleSettingsOverlayClick(double mouseX, double mouseY) {
        int buttonY = topPos + 130;

        if (deleteHistoryConfirmPending) {
            int confirmX = leftPos + (imageWidth - CONFIRM_BUTTON_WIDTH * 2 - 6) / 2;
            int cancelX = confirmX + CONFIRM_BUTTON_WIDTH + 6;
            if (isMouseOverRect(mouseX, mouseY, confirmX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                PacketDistributor.sendToServer(new ChronosphereDeleteHistoryPacket(menu.getBlockPos()));
                deleteHistoryConfirmPending = false;
                settingsOverlayOpen = false;
            } else if (isMouseOverRect(mouseX, mouseY, cancelX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                deleteHistoryConfirmPending = false;
            }
        } else {
            int buttonX = leftPos + (imageWidth - DELETE_BUTTON_WIDTH) / 2;
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
        PacketDistributor.sendToServer(new RollbackChunkPacket(menu.getBlockPos(), target));
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    private static Component showChangesLabel(boolean enabled) {
        return Component.translatable(enabled
                ? "gui.temporalindustries.time_machine.hide_changes"
                : "gui.temporalindustries.time_machine.show_changes");
    }

    private long getCurrentGameTime() {
        if (minecraft == null || minecraft.level == null) {
            return 0L;
        }
        return minecraft.level.getGameTime();
    }

    // -------------------------------------------------------------------------
    // Rendering

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xE0101010);
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, 0xFF555555);

        if (!mapOverlayOpen) renderChunkTabs(guiGraphics, mouseX, mouseY);
        graphWidget.render(guiGraphics, font, leftPos + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight());
        renderEnergyBar(guiGraphics);
    }

    private int graphTop() {
        return topPos + GRAPH_Y_OFFSET + TAB_STRIP_HEIGHT;
    }

    private int graphHeight() {
        return GRAPH_HEIGHT - TAB_STRIP_HEIGHT;
    }

    /** Renders the "All" + per-claimed-chunk tab strip along the top of the graph, sized to fit
     * however many chunks are currently claimed within GRAPH_WIDTH. */
    private void renderChunkTabs(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<Long> chunkKeys = getOrderedTabChunkKeys();
        int tabCount = chunkKeys.size() + 1; // + the "All" tab
        int stripX = leftPos + GRAPH_X_OFFSET;
        int stripY = topPos + GRAPH_Y_OFFSET;
        int tabWidth = Math.max(TAB_MIN_WIDTH, Math.min(TAB_MAX_WIDTH, (GRAPH_WIDTH - (tabCount - 1) * TAB_GAP) / tabCount));

        long homeKey = new ChunkPos(menu.getBlockPos()).toLong();
        int x = stripX;
        x = renderTab(guiGraphics, x, stripY, tabWidth, "S", selectedViewChunkKey == null, mouseX, mouseY, null);
        for (long key : chunkKeys) {
            if (x >= stripX + GRAPH_WIDTH) break; // out of room: remaining chunks are click-reachable only via scroll (future work)
            String label = key == homeKey ? "H" : "";
            boolean selected = selectedViewChunkKey != null && selectedViewChunkKey == key;
            x = renderTab(guiGraphics, x, stripY, tabWidth, label, selected, mouseX, mouseY, key);
        }
    }

    /** Draws one tab and returns the x position the next tab should start at. */
    private int renderTab(GuiGraphics guiGraphics, int x, int y, int width, String label, boolean selected,
                           int mouseX, int mouseY, @Nullable Long chunkKey) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + TAB_STRIP_HEIGHT;
        int bg = selected ? 0xFF66C7FF : (hovered ? 0xFF3A3A3A : 0xFF1E1E1E);
        guiGraphics.fill(x, y, x + width, y + TAB_STRIP_HEIGHT, COLOR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + TAB_STRIP_HEIGHT - 1, bg);
        if (!label.isEmpty() && width >= 10) {
            int textColor = selected ? 0xFF10151A : 0xFFBFBFBF;
            guiGraphics.drawCenteredString(font, label, x + width / 2, y + 2, textColor);
        }
        return x + width + TAB_GAP;
    }

    /** Which tab (null = "All", -1L sentinel meaning "no tab" via a boolean out-param would be
     * awkward, so this returns Optional-like via a wrapper) is under the cursor, or null if none —
     * used for both click handling and tooltips. */
    @Nullable
    private TabHit getTabAt(double mouseX, double mouseY) {
        int stripY = topPos + GRAPH_Y_OFFSET;
        if (mouseY < stripY || mouseY >= stripY + TAB_STRIP_HEIGHT) return null;

        List<Long> chunkKeys = getOrderedTabChunkKeys();
        int tabCount = chunkKeys.size() + 1;
        int stripX = leftPos + GRAPH_X_OFFSET;
        int tabWidth = Math.max(TAB_MIN_WIDTH, Math.min(TAB_MAX_WIDTH, (GRAPH_WIDTH - (tabCount - 1) * TAB_GAP) / tabCount));

        if (mouseX < stripX || mouseX >= stripX + GRAPH_WIDTH) return null;
        int index = (int) ((mouseX - stripX) / (tabWidth + TAB_GAP));
        if (index == 0) return new TabHit(null);
        int chunkIndex = index - 1;
        if (chunkIndex < 0 || chunkIndex >= chunkKeys.size()) return null;
        return new TabHit(chunkKeys.get(chunkIndex));
    }

    private record TabHit(@Nullable Long chunkKey) {}

    private void renderEnergyBar(GuiGraphics guiGraphics) {
        int energyStored = menu.getEnergyStored();
        int energyCapacity = menu.getEnergyCapacity();

        int barX = leftPos + ENERGY_BAR_X_OFFSET;
        int barY = topPos + ENERGY_BAR_Y_OFFSET;

        guiGraphics.fill(barX, barY, barX + ENERGY_BAR_WIDTH, barY + ENERGY_BAR_HEIGHT, 0xFF000000);
        if (energyCapacity > 0 && energyStored > 0) {
            int filled = Math.max(1, Math.round((energyStored / (float) energyCapacity) * (ENERGY_BAR_WIDTH - 2)));
            guiGraphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + ENERGY_BAR_HEIGHT - 1, 0xFF4DD0E1);
        }
    }

    private boolean isMouseOverEnergyBar(int mouseX, int mouseY) {
        int barX = leftPos + ENERGY_BAR_X_OFFSET;
        int barY = topPos + ENERGY_BAR_Y_OFFSET;
        return mouseX >= barX && mouseX <= barX + ENERGY_BAR_WIDTH && mouseY >= barY && mouseY <= barY + ENERGY_BAR_HEIGHT;
    }

    private void renderBookmark(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean hovered = isMouseOverBookmark(mouseX, mouseY);
        int bg = mapOverlayOpen ? 0xFF66C7FF : (hovered ? 0xFF3A3A3A : 0xFF2A2A2A);

        guiGraphics.fill(bookmarkX, bookmarkY, bookmarkX + BOOKMARK_SIZE, bookmarkY + BOOKMARK_SIZE, COLOR_BORDER);
        guiGraphics.fill(bookmarkX + 1, bookmarkY + 1, bookmarkX + BOOKMARK_SIZE - 1, bookmarkY + BOOKMARK_SIZE - 1, bg);

        // Placeholder icon: a little 3x3 grid glyph, echoing the claim map it opens.
        int glyphColor = mapOverlayOpen ? 0xFF10151A : 0xFFBFBFBF;
        int cell = 5;
        int gap = 2;
        int glyphSize = cell * 3 + gap * 2;
        int glyphX = bookmarkX + (BOOKMARK_SIZE - glyphSize) / 2;
        int glyphY = bookmarkY + (BOOKMARK_SIZE - glyphSize) / 2;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int cx = glyphX + col * (cell + gap);
                int cy = glyphY + row * (cell + gap);
                guiGraphics.fill(cx, cy, cx + cell, cy + cell, glyphColor);
            }
        }
    }

    private boolean isMouseOverBookmark(double mouseX, double mouseY) {
        return mouseX >= bookmarkX && mouseX <= bookmarkX + BOOKMARK_SIZE
                && mouseY >= bookmarkY && mouseY <= bookmarkY + BOOKMARK_SIZE;
    }

    private void renderAutoTrackTab(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean enabled = ChronosphereClientState.isAutoTrackingEnabled();
        boolean hovered = isMouseOverAutoTrackTab(mouseX, mouseY);
        int bg = enabled ? 0xFFCC5555 : (hovered ? 0xFF3A3A3A : 0xFF2A2A2A);

        guiGraphics.fill(autoTrackX, autoTrackY, autoTrackX + BOOKMARK_SIZE, autoTrackY + BOOKMARK_SIZE, COLOR_BORDER);
        guiGraphics.fill(autoTrackX + 1, autoTrackY + 1, autoTrackX + BOOKMARK_SIZE - 1, autoTrackY + BOOKMARK_SIZE - 1, bg);

        // Placeholder icon: a small filled "record" dot, echoing a recording indicator.
        int glyphColor = enabled ? 0xFFFFDDDD : 0xFFBFBFBF;
        int dotSize = 10;
        int dotX = autoTrackX + (BOOKMARK_SIZE - dotSize) / 2;
        int dotY = autoTrackY + (BOOKMARK_SIZE - dotSize) / 2;
        guiGraphics.fill(dotX, dotY, dotX + dotSize, dotY + dotSize, glyphColor);
    }

    private boolean isMouseOverAutoTrackTab(double mouseX, double mouseY) {
        return mouseX >= autoTrackX && mouseX <= autoTrackX + BOOKMARK_SIZE
                && mouseY >= autoTrackY && mouseY <= autoTrackY + BOOKMARK_SIZE;
    }

    /** Placeholder glyph until a real icon is made — a plain gray square, distinct enough from the
     * auto-track dot and the bookmark tab not to be confused with either. */
    private void renderSettingsTab(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean hovered = isMouseOverSettingsTab(mouseX, mouseY);
        int bg = hovered ? 0xFF3A3A3A : 0xFF2A2A2A;

        guiGraphics.fill(settingsX, settingsY, settingsX + BOOKMARK_SIZE, settingsY + BOOKMARK_SIZE, COLOR_BORDER);
        guiGraphics.fill(settingsX + 1, settingsY + 1, settingsX + BOOKMARK_SIZE - 1, settingsY + BOOKMARK_SIZE - 1, bg);

        int glyphSize = 10;
        int glyphX = settingsX + (BOOKMARK_SIZE - glyphSize) / 2;
        int glyphY = settingsY + (BOOKMARK_SIZE - glyphSize) / 2;
        guiGraphics.fill(glyphX, glyphY, glyphX + glyphSize, glyphY + glyphSize, 0xFFBFBFBF);
    }

    private boolean isMouseOverSettingsTab(double mouseX, double mouseY) {
        return mouseX >= settingsX && mouseX <= settingsX + BOOKMARK_SIZE
                && mouseY >= settingsY && mouseY <= settingsY + BOOKMARK_SIZE;
    }

    private void renderSettingsOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0101010);

        guiGraphics.drawCenteredString(font, Component.translatable("gui.temporalindustries.chronosphere.settings_title"),
                leftPos + imageWidth / 2, topPos + 16, 0xFFFFFF);

        guiGraphics.drawWordWrap(font, Component.translatable("gui.temporalindustries.chronosphere.delete_history_hint"),
                leftPos + 20, topPos + 60, imageWidth - 40, 0xFFBFBFBF);

        int buttonX = leftPos + (imageWidth - DELETE_BUTTON_WIDTH) / 2;
        int buttonY = topPos + 130;

        if (deleteHistoryConfirmPending) {
            guiGraphics.drawCenteredString(font, Component.translatable("gui.temporalindustries.chronosphere.delete_history_confirm_prompt"),
                    leftPos + imageWidth / 2, buttonY - 14, 0xFFFF6E6E);

            int confirmX = leftPos + (imageWidth - CONFIRM_BUTTON_WIDTH * 2 - 6) / 2;
            int cancelX = confirmX + CONFIRM_BUTTON_WIDTH + 6;
            drawOverlayButton(guiGraphics, confirmX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.chronosphere.delete_history_confirm_button"),
                    0xFF8A3A3A, isMouseOverRect(mouseX, mouseY, confirmX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
            drawOverlayButton(guiGraphics, cancelX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.chronosphere.delete_history_cancel_button"),
                    0xFF3A3A3A, isMouseOverRect(mouseX, mouseY, cancelX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
        } else {
            drawOverlayButton(guiGraphics, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.chronosphere.delete_history_button"),
                    0xFF8A3A3A, isMouseOverRect(mouseX, mouseY, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
        }
    }

    private void drawOverlayButton(GuiGraphics guiGraphics, int x, int y, int width, int height,
                                   Component label, int baseColor, boolean hovered) {
        int bg = hovered ? brighten(baseColor) : baseColor;
        guiGraphics.fill(x, y, x + width, y + height, COLOR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, bg);
        guiGraphics.drawCenteredString(font, label, x + width / 2, y + (height - 8) / 2, 0xFFFFFFFF);
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

    private void renderMapOverlay(GuiGraphics guiGraphics) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0101010);

        guiGraphics.drawCenteredString(font, Component.translatable("gui.temporalindustries.chronosphere.map_title"),
                leftPos + imageWidth / 2, topPos + 16, 0xFFFFFF);

        ChunkPos home = new ChunkPos(menu.getBlockPos());
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                int dx = col - RADIUS;
                int dz = row - RADIUS;
                if (!ChronosphereBlockEntity.isWithinRadius(dx, dz)) continue; // outside the circle: leave blank

                int cellX = gridX + col * (CELL_SIZE + CELL_GAP);
                int cellY = gridY + row * (CELL_SIZE + CELL_GAP);
                long key = new ChunkPos(home.x + dx, home.z + dz).toLong();

                int tint;
                if (dx == 0 && dz == 0) {
                    tint = COLOR_HOME;
                } else if (ChronosphereClientState.isSelected(key)) {
                    tint = COLOR_SELECTED;
                } else if (ChronosphereClientState.isBlocked(key)) {
                    tint = COLOR_BLOCKED;
                } else {
                    tint = COLOR_AVAILABLE;
                }

                guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, COLOR_BORDER);

                ResourceLocation terrain = ChronosphereMapClientState.getTexture(key);
                int innerX0 = cellX + 1, innerY0 = cellY + 1, innerX1 = cellX + CELL_SIZE - 1, innerY1 = cellY + CELL_SIZE - 1;
                if (terrain != null) {
                    int inner = CELL_SIZE - 2;
                    int size = ChronoMapSampler.SIZE;
                    guiGraphics.blit(terrain, innerX0, innerY0, inner, inner, 0.0F, 0.0F, size, size, size, size);
                    // Status tint over the terrain, translucent so the sampled ground stays visible.
                    guiGraphics.fill(innerX0, innerY0, innerX1, innerY1, (0x80 << 24) | (tint & 0xFFFFFF));
                } else {
                    // No thumbnail yet (overlay just opened, or the chunk isn't loaded server-side):
                    // fall back to the flat status color instead of leaving the cell blank.
                    guiGraphics.fill(innerX0, innerY0, innerX1, innerY1, tint);
                }
            }
        }

        // menu.getBlockEntity() is the CLIENT's copy of the block entity, which (having no custom
        // getUpdateTag/handleUpdateTag override) never receives its fields from the server — its
        // additionalChunks set is always empty client-side, so getChunkCount() would always read
        // 1 here. ChronosphereClientState's synced selection is the only client-accurate source.
        int claimedCount = ChronosphereClientState.getSelectedCount();
        int footerY = gridY + GRID_PIXELS + 10;
        guiGraphics.drawCenteredString(font, Component.literal(
                claimedCount + " / " + TOTAL_CLAIMABLE + " chunks claimed"),
                leftPos + imageWidth / 2, footerY, 0xFFBFBFBF);
        int trackedCount = ChronosphereClientState.getTrackedCount();
        int trackedColor = trackedCount == claimedCount ? 0xFF8CFF9E : 0xFFFFB86B;
        guiGraphics.drawCenteredString(font, Component.literal(
                trackedCount + " / " + claimedCount + " chunks tracked"),
                leftPos + imageWidth / 2, footerY + 11, trackedColor);
        guiGraphics.drawCenteredString(font, Component.translatable("gui.temporalindustries.chronosphere.map_hint"),
                leftPos + imageWidth / 2, footerY + 23, 0xFF808080);
    }

    private ChunkPos getGridCellAt(double mouseX, double mouseY) {
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + GRID_PIXELS || mouseY >= gridY + GRID_PIXELS) {
            return null;
        }
        int col = (int) ((mouseX - gridX) / (CELL_SIZE + CELL_GAP));
        int row = (int) ((mouseY - gridY) / (CELL_SIZE + CELL_GAP));
        double cellLocalX = (mouseX - gridX) - col * (CELL_SIZE + CELL_GAP);
        double cellLocalY = (mouseY - gridY) - row * (CELL_SIZE + CELL_GAP);
        if (cellLocalX > CELL_SIZE || cellLocalY > CELL_SIZE) return null; // clicked in the gap
        if (col < 0 || col >= GRID_SIZE || row < 0 || row >= GRID_SIZE) return null;

        int dx = col - RADIUS;
        int dz = row - RADIUS;
        if (!ChronosphereBlockEntity.isWithinRadius(dx, dz)) return null; // outside the circle: not drawn, not clickable

        ChunkPos home = new ChunkPos(menu.getBlockPos());
        return new ChunkPos(home.x + dx, home.z + dz);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderLabels(guiGraphics, mouseX, mouseY);
        for (var widget : renderables) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (mapOverlayOpen) {
            renderMapOverlay(guiGraphics);
        } else if (settingsOverlayOpen) {
            renderSettingsOverlay(guiGraphics, mouseX, mouseY);
        }
        renderBookmark(guiGraphics, mouseX, mouseY);
        renderAutoTrackTab(guiGraphics, mouseX, mouseY);
        renderSettingsTab(guiGraphics, mouseX, mouseY);

        if (mapOverlayOpen) {
            if (isMouseOverBookmark(mouseX, mouseY)) {
                guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronosphere.map_tooltip"), mouseX, mouseY);
            } else if (isMouseOverAutoTrackTab(mouseX, mouseY)) {
                guiGraphics.renderTooltip(font, autoTrackTooltip(), mouseX, mouseY);
            }
            return;
        }
        if (settingsOverlayOpen) {
            return;
        }

        TabHit hoveredTab = getTabAt(mouseX, mouseY);
        if (hoveredTab != null) {
            Component label = hoveredTab.chunkKey() == null
                    ? Component.literal("Shared history (all claimed chunks)")
                    : Component.literal("Chunk " + new ChunkPos(hoveredTab.chunkKey()).x + ", " + new ChunkPos(hoveredTab.chunkKey()).z);
            guiGraphics.renderTooltip(font, label, mouseX, mouseY);
            return;
        }

        List<FormattedCharSequence> tooltip = graphWidget.getTooltipAt(mouseX, mouseY);
        if (!tooltip.isEmpty()) {
            guiGraphics.renderTooltip(font, tooltip, mouseX, mouseY);
        } else if (isMouseOverEnergyBar(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getEnergyStored() + " / " + menu.getEnergyCapacity() + " FE"), mouseX, mouseY);
        } else if (isMouseOverBookmark(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronosphere.map_tooltip"), mouseX, mouseY);
        } else if (isMouseOverAutoTrackTab(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, autoTrackTooltip(), mouseX, mouseY);
        } else if (isMouseOverSettingsTab(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronosphere.settings_tooltip"), mouseX, mouseY);
        }
    }

    private static Component autoTrackTooltip() {
        return Component.translatable(ChronosphereClientState.isAutoTrackingEnabled()
                ? "gui.temporalindustries.chronosphere.auto_track_tooltip_on"
                : "gui.temporalindustries.chronosphere.auto_track_tooltip_off");
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, Component.translatable("block.temporalindustries.chronosphere"), leftPos + 8, topPos + 8, 0xFFFFFF, false);

        long now = TimelineProjectionManager.getCurrentGameTime();
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.time_machine.preview_current", formatGameDayTime(now)), leftPos + 8, topPos + 200, 0xFFFFFF, false);

        if (TimelineProjectionManager.hasSelection()) {
            long selected = TimelineProjectionManager.getSelectedGameTime();
            long diff = selected - now;
            String direction = diff <= 0L
                    ? "gui.temporalindustries.time_machine.preview_past"
                    : "gui.temporalindustries.time_machine.preview_future";
            guiGraphics.drawString(font, Component.translatable(direction, formatSincePlaced(Math.abs(diff))), leftPos + 8, topPos + 210, 0xFFFFFF, false);
        }
    }

    // -------------------------------------------------------------------------
    // Interaction

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOverBookmark(mouseX, mouseY)) {
            mapOverlayOpen = !mapOverlayOpen;
            settingsOverlayOpen = false;
            deleteHistoryConfirmPending = false;
            if (mapOverlayOpen) {
                ticksSinceMapSync = 0;
                PacketDistributor.sendToServer(new ChronosphereMapRequestPacket(menu.getBlockPos()));
            }
            return true;
        }

        if (button == 0 && isMouseOverAutoTrackTab(mouseX, mouseY)) {
            boolean newState = !ChronosphereClientState.isAutoTrackingEnabled();
            PacketDistributor.sendToServer(new ChronosphereToggleAutoTrackPacket(menu.getBlockPos(), newState));
            return true;
        }

        if (button == 0 && isMouseOverSettingsTab(mouseX, mouseY)) {
            settingsOverlayOpen = !settingsOverlayOpen;
            mapOverlayOpen = false;
            deleteHistoryConfirmPending = false;
            return true;
        }

        if (mapOverlayOpen) {
            if (button == 0) {
                ChunkPos clicked = getGridCellAt(mouseX, mouseY);
                if (clicked != null && !clicked.equals(new ChunkPos(menu.getBlockPos()))) {
                    long key = clicked.toLong();
                    boolean add = !ChronosphereClientState.isSelected(key);
                    if (!(add && ChronosphereClientState.isBlocked(key))) {
                        PacketDistributor.sendToServer(new ChronosphereToggleChunkPacket(menu.getBlockPos(), key, add));
                    }
                }
            }
            // The overlay is modal: swallow every click on the panel while it's open so nothing
            // underneath (the graph, the buttons) reacts to it.
            return true;
        }

        if (settingsOverlayOpen) {
            if (button == 0) {
                handleSettingsOverlayClick(mouseX, mouseY);
            }
            // Modal, same as the claim map overlay above.
            return true;
        }

        if (button == 0) {
            TabHit tabHit = getTabAt(mouseX, mouseY);
            if (tabHit != null) {
                if (!java.util.Objects.equals(selectedViewChunkKey, tabHit.chunkKey())) {
                    selectedViewChunkKey = tabHit.chunkKey();
                    TimelineProjectionManager.setSelectedViewChunk(
                            selectedViewChunkKey == null ? null : new ChunkPos(selectedViewChunkKey));
                    TimelineProjectionManager.clearSelectedCommit();
                    requestTimelineView(true);
                }
                return true;
            }
        }

        if (button == 0 && graphWidget.mouseClicked(mouseX, mouseY, leftPos + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight())) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (mapOverlayOpen || settingsOverlayOpen) {
            return true;
        }
        if (graphWidget.mouseDragged(dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (mapOverlayOpen) {
            return true;
        }
        if (button == 0 && graphWidget.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mapOverlayOpen) {
            return true;
        }
        if (graphWidget.mouseScrolled(mouseX, mouseY, scrollY, leftPos + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight())) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
