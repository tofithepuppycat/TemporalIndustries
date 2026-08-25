package io.github.tofithepuppycat.temporalindustries.client.screen;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.client.IconTabRenderer;
import io.github.tofithepuppycat.temporalindustries.client.ChunkThumbnailClientState;
import io.github.tofithepuppycat.temporalindustries.client.chunkmap.ChunkSelectionGrid;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineDeleteHistoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereMapRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereStateRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineToggleAutoTrackPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereToggleChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.RollbackChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/** GUI for the Chronosphere block: the same timeline graph view as the Chronovault (via
 * {@link TimelineGraphWidget}), reading/jumping the home chunk's history exactly like a
 * Chronovault — except Jump moves every chunk this Chronosphere has claimed, not just the one shown.
 * The 11x11 claim map lives behind a square bookmark tab on the panel's side and opens as an
 * overlay, rather than occupying the main view. */
@SuppressWarnings("null")
public class ChronosphereScreen extends AbstractContainerScreen<ChronosphereMenu> {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/base.png");
    private static final ResourceLocation ICON_CONFIG_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/icon_config.png");
    private static final ResourceLocation ICON_EYE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/icon_eye.png");
    private static final ResourceLocation ICON_JUMP_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/icon_jump.png");
    private static final int ICON_CONFIG_SIZE = 26;

    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_HEIGHT = 256;
    /** base.png's actual panel artwork sits inset this many pixels on every side within the
     * 256x256 canvas — every layout constant below is measured from the artwork's edge (via
     * {@link #panelX()}/{@link #panelY()}), not from leftPos/topPos directly, and CONTENT_SIZE
     * (not imageWidth) is the usable width/height for centering and right-edge anchoring. */
    private static final int CONTENT_MARGIN = 16;
    private static final int CONTENT_SIZE = IMAGE_WIDTH - 2 * CONTENT_MARGIN;

    // base.png is a light panel, so text/UI accents are tuned for a light background rather than
    // the dark theme the rest of the mod's placeholder GUIs still use.
    private static final int TEXT_PRIMARY = 0xFF2B2B2B;
    private static final int TEXT_SECONDARY = 0xFF5A5A5A;
    private static final int TEXT_MUTED = 0xFF7A7A7A;

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

    private static final int ENTROPY_BAR_X_OFFSET = 6;
    private static final int ENTROPY_BAR_Y_OFFSET = 7;
    private static final int ENTROPY_BAR_WIDTH = 52;
    private static final int ENTROPY_BAR_HEIGHT = 8;
    private static final int COLOR_ORDER_BAR = 0xFF000000 | EntropyType.ORDER.color();
    private static final int COLOR_CHAOS_BAR = 0xFF000000 | EntropyType.CHAOS.color();

    private static final int PREVIEW_CURRENT_Y_OFFSET = 176;
    private static final int PREVIEW_DIFF_Y_OFFSET = 186;
    private static final int BUTTON_ROW_Y_OFFSET = 200;
    private static final int ACTION_BUTTON_SIZE = IconButtonRenderer.SIZE;
    private static final int ACTION_BUTTON_GAP = 6;

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

    // Bookmark/auto-track/settings tabs: a vertical stack mostly overlapping the panel's right
    // edge, protruding outward, like a vanilla recipe-book tab attached to a crafting GUI — see
    // IconTabRenderer for the layered sprite, and render()'s z-order note for why they're drawn
    // BEFORE the panel background in the non-overlay case (so the panel's edge paints over the
    // small overlap sliver, tucking the tab in rather than stacking it visibly on top).
    private static final int TAB_ROW_SIZE = IconTabRenderer.SIZE;
    private static final int TAB_OVERLAP = 5;
    private static final int BOOKMARK_Y_OFFSET = 40;
    private static final int TAB_ROW_SPACING = 4;
    private static final int AUTO_TRACK_Y_OFFSET = BOOKMARK_Y_OFFSET + TAB_ROW_SIZE + TAB_ROW_SPACING;
    private static final int SETTINGS_Y_OFFSET = AUTO_TRACK_Y_OFFSET + TAB_ROW_SIZE + TAB_ROW_SPACING;
    private static final int DELETE_BUTTON_WIDTH = 140;
    private static final int DELETE_BUTTON_HEIGHT = 20;
    private static final int CONFIRM_BUTTON_WIDTH = 66;

    /** 15px cells rather than the grid's 32px default: at MAX_RADIUS=5 the map is 11 cells across,
     * and only this size leaves the title above and the three footer lines below room inside
     * CONTENT_SIZE. */
    private static final int MAP_CELL_SIZE = 15;
    private static final ChunkSelectionGrid MAP_GRID = new ChunkSelectionGrid(
            ChronosphereBlockEntity.MAX_RADIUS, ChronosphereBlockEntity.CLAIM_SHAPE, MAP_CELL_SIZE);
    private static final int TOTAL_CLAIMABLE = countClaimableCells();

    // The map overlay's grid (165px tall at MAX_RADIUS=5) eats most of CONTENT_SIZE's 224px, so
    // its title/grid/footer are packed tighter than the settings overlay's equivalents.
    private static final int MAP_TITLE_Y_OFFSET = 6;
    private static final int MAP_GRID_Y_OFFSET = 18;
    private static final int MAP_FOOTER_GAP = 4;
    private static final int MAP_FOOTER_LINE_SPACING = 10;

    private static final int COLOR_HOME       = 0xFFFFD166;
    private static final int COLOR_SELECTED   = 0xFF3D9BE0;
    private static final int COLOR_BLOCKED    = 0xFFB55050;
    private static final int COLOR_AVAILABLE  = 0xFFAFAFAF;
    private static final int COLOR_BORDER     = 0xFF000000;

    private final TimelineGraphWidget graphWidget = new TimelineGraphWidget();

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
    private int showChangesX;
    private int showChangesY;
    private int jumpX;
    private int jumpY;

    public ChronosphereScreen(ChronosphereMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = imageHeight + 100; // Push off-screen to hide inventory
    }

    private static int countClaimableCells() {
        int radius = ChronosphereBlockEntity.MAX_RADIUS;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (ChronosphereBlockEntity.isWithinRadius(dx, dz)) count++;
            }
        }
        return count;
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

        bookmarkX = panelX() + CONTENT_SIZE - TAB_OVERLAP;
        bookmarkY = panelY() + BOOKMARK_Y_OFFSET;
        autoTrackX = panelX() + CONTENT_SIZE - TAB_OVERLAP;
        autoTrackY = panelY() + AUTO_TRACK_Y_OFFSET;
        settingsX = panelX() + CONTENT_SIZE - TAB_OVERLAP;
        settingsY = panelY() + SETTINGS_Y_OFFSET;
        gridX = panelX() + (CONTENT_SIZE - MAP_GRID.gridPixels()) / 2;
        gridY = panelY() + MAP_GRID_Y_OFFSET;

        int groupX = panelX() + (CONTENT_SIZE - (ACTION_BUTTON_SIZE * 2 + ACTION_BUTTON_GAP)) / 2;
        showChangesX = groupX;
        jumpX = groupX + ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP;
        showChangesY = panelY() + BUTTON_ROW_Y_OFFSET;
        jumpY = showChangesY;

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
        long lastKnownHead = forceFull ? Long.MIN_VALUE : TimelineProjectionManager.getHeadCommitId();
        long lastKnownPreviewVersion = forceFull ? Long.MIN_VALUE : TimelineProjectionManager.getPreviewVersion();
        PacketDistributor.sendToServer(new TimelinePreviewRequestPacket(
                menu.getBlockPos(), lastKnownHead, lastKnownPreviewVersion, viewChunk));
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
        ChunkThumbnailClientState.clearAll();
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
    }

    /** Mirrors the button rects renderSettingsOverlay draws, since they're plain fills rather than
     * Button widgets (consistent with the claim map overlay's own click handling). */
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

    // -------------------------------------------------------------------------
    // Rendering

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        if (!mapOverlayOpen) renderChunkTabs(guiGraphics, mouseX, mouseY);
        graphWidget.render(guiGraphics, font, panelX() + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight());
        renderEnergyBar(guiGraphics);
        renderEntropyBar(guiGraphics);

        if (!mapOverlayOpen && !settingsOverlayOpen) {
            renderActionButtons(guiGraphics, mouseX, mouseY);
        }
    }

    /** Show Changes / Jump: small icon buttons (menu_icon_base_small.png + their own icon) in
     * place of vanilla Buttons — Show Changes tints on while active, Jump dims while there's
     * nothing selected to jump to. */
    private void renderActionButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean showChangesEnabled = TimelineProjectionManager.isShowChangesEnabled();
        int showChangesTint = showChangesEnabled ? 0xB0CC5555 : (isMouseOverShowChangesButton(mouseX, mouseY) ? 0x40000000 : 0);
        IconButtonRenderer.renderBackground(guiGraphics, showChangesX, showChangesY, showChangesTint);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_EYE_TEXTURE, showChangesX, showChangesY);

        boolean jumpActive = TimelineProjectionManager.hasSelection();
        int jumpTint = !jumpActive ? 0x80000000 : (isMouseOverJumpButton(mouseX, mouseY) ? 0x40FFFFFF : 0);
        IconButtonRenderer.renderBackground(guiGraphics, jumpX, jumpY, jumpTint);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_JUMP_TEXTURE, jumpX, jumpY);
    }

    private boolean isMouseOverShowChangesButton(double mouseX, double mouseY) {
        return mouseX >= showChangesX && mouseX <= showChangesX + ACTION_BUTTON_SIZE
                && mouseY >= showChangesY && mouseY <= showChangesY + ACTION_BUTTON_SIZE;
    }

    private boolean isMouseOverJumpButton(double mouseX, double mouseY) {
        return mouseX >= jumpX && mouseX <= jumpX + ACTION_BUTTON_SIZE
                && mouseY >= jumpY && mouseY <= jumpY + ACTION_BUTTON_SIZE;
    }

    private int graphTop() {
        return panelY() + GRAPH_Y_OFFSET + TAB_STRIP_HEIGHT;
    }

    private int graphHeight() {
        return GRAPH_HEIGHT - TAB_STRIP_HEIGHT;
    }

    /** Renders the "All" + per-claimed-chunk tab strip along the top of the graph, sized to fit
     * however many chunks are currently claimed within GRAPH_WIDTH. */
    private void renderChunkTabs(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<Long> chunkKeys = getOrderedTabChunkKeys();
        int tabCount = chunkKeys.size() + 1; // + the "All" tab
        int stripX = panelX() + GRAPH_X_OFFSET;
        int stripY = panelY() + GRAPH_Y_OFFSET;
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
        int bg = selected ? COLOR_SELECTED : (hovered ? 0xFFB8B8B8 : 0xFFCFCFCF);
        guiGraphics.fill(x, y, x + width, y + TAB_STRIP_HEIGHT, COLOR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + TAB_STRIP_HEIGHT - 1, bg);
        if (!label.isEmpty() && width >= 10) {
            int textColor = selected ? 0xFFF0FAFF : TEXT_PRIMARY;
            drawCenteredNoShadow(guiGraphics, label, x + width / 2, y + 2, textColor);
        }
        return x + width + TAB_GAP;
    }

    /** Which tab (null = "All", -1L sentinel meaning "no tab" via a boolean out-param would be
     * awkward, so this returns Optional-like via a wrapper) is under the cursor, or null if none —
     * used for both click handling and tooltips. */
    @Nullable
    private TabHit getTabAt(double mouseX, double mouseY) {
        int stripY = panelY() + GRAPH_Y_OFFSET;
        if (mouseY < stripY || mouseY >= stripY + TAB_STRIP_HEIGHT) return null;

        List<Long> chunkKeys = getOrderedTabChunkKeys();
        int tabCount = chunkKeys.size() + 1;
        int stripX = panelX() + GRAPH_X_OFFSET;
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

        int barX = panelX() + ENERGY_BAR_X_OFFSET;
        int barY = panelY() + ENERGY_BAR_Y_OFFSET;

        guiGraphics.fill(barX, barY, barX + ENERGY_BAR_WIDTH, barY + ENERGY_BAR_HEIGHT, 0xFF000000);
        if (energyCapacity > 0 && energyStored > 0) {
            int filled = Math.max(1, Math.round((energyStored / (float) energyCapacity) * (ENERGY_BAR_WIDTH - 2)));
            guiGraphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + ENERGY_BAR_HEIGHT - 1, 0xFF3BFB98);
        }
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
                guiGraphics.fill(mid, barY + 1, mid + filled, barY + ENTROPY_BAR_HEIGHT - 1, COLOR_CHAOS_BAR);
            } else {
                guiGraphics.fill(mid - filled, barY + 1, mid, barY + ENTROPY_BAR_HEIGHT - 1, COLOR_ORDER_BAR);
            }
        }
        guiGraphics.fill(mid, barY, mid + 1, barY + ENTROPY_BAR_HEIGHT, 0xFF888888);
    }

    private boolean isMouseOverEntropyBar(int mouseX, int mouseY) {
        int barX = panelX() + ENTROPY_BAR_X_OFFSET;
        int barY = panelY() + ENTROPY_BAR_Y_OFFSET;
        return mouseX >= barX && mouseX <= barX + ENTROPY_BAR_WIDTH && mouseY >= barY && mouseY <= barY + ENTROPY_BAR_HEIGHT;
    }

    private void renderBookmark(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean hovered = isMouseOverBookmark(mouseX, mouseY);
        int tint = mapOverlayOpen ? (0xB0 << 24 | (COLOR_SELECTED & 0xFFFFFF)) : (hovered ? 0x40000000 : 0);

        IconTabRenderer.renderBackground(guiGraphics, bookmarkX, bookmarkY, tint);

        // Placeholder icon: a little 3x3 grid glyph, echoing the claim map it opens.
        int glyphColor = mapOverlayOpen ? 0xFFF0FAFF : TEXT_PRIMARY;
        int cell = 5;
        int gap = 2;
        int glyphSize = cell * 3 + gap * 2;
        int glyphX = bookmarkX + (TAB_ROW_SIZE - glyphSize) / 2 + IconTabRenderer.ICON_X_NUDGE;
        int glyphY = bookmarkY + (TAB_ROW_SIZE - glyphSize) / 2;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int cx = glyphX + col * (cell + gap);
                int cy = glyphY + row * (cell + gap);
                guiGraphics.fill(cx, cy, cx + cell, cy + cell, glyphColor);
            }
        }
    }

    private boolean isMouseOverBookmark(double mouseX, double mouseY) {
        return mouseX >= bookmarkX && mouseX <= bookmarkX + TAB_ROW_SIZE
                && mouseY >= bookmarkY && mouseY <= bookmarkY + TAB_ROW_SIZE;
    }

    private void renderAutoTrackTab(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean enabled = ChronosphereClientState.isAutoTrackingEnabled();
        boolean hovered = isMouseOverAutoTrackTab(mouseX, mouseY);
        int tint = enabled ? 0xB0CC5555 : (hovered ? 0x40000000 : 0);

        IconTabRenderer.renderBackground(guiGraphics, autoTrackX, autoTrackY, tint);

        // Placeholder icon: a small filled "record" dot, echoing a recording indicator.
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
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

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

    // GuiGraphics#drawCenteredString/#drawWordWrap always draw with a drop shadow and have no
    // no-shadow overload; against this light panel the shadow reads as ghosting rather than
    // contrast, so these draw centered/wrapped text without one instead.
    private void drawCenteredNoShadow(GuiGraphics guiGraphics, Component text, int centerX, int y, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private void drawCenteredNoShadow(GuiGraphics guiGraphics, String text, int centerX, int y, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private void drawWordWrapNoShadow(GuiGraphics guiGraphics, Component text, int x, int y, int maxWidth, int color) {
        int lineY = y;
        for (FormattedCharSequence line : font.split(text, maxWidth)) {
            guiGraphics.drawString(font, line, x, lineY, color, false);
            lineY += font.lineHeight;
        }
    }

    private void renderMapOverlay(GuiGraphics guiGraphics) {
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.chronosphere.map_title"),
                panelX() + CONTENT_SIZE / 2, panelY() + MAP_TITLE_Y_OFFSET, TEXT_PRIMARY);

        ChunkPos home = new ChunkPos(menu.getBlockPos());
        MAP_GRID.render(guiGraphics, gridX, gridY, home, new ChunkSelectionGrid.CellPainter() {
            @Override
            public int tint(int dx, int dz, long chunkKey) {
                if (dx == 0 && dz == 0) return COLOR_HOME;
                if (ChronosphereClientState.isSelected(chunkKey)) return COLOR_SELECTED;
                if (ChronosphereClientState.isBlocked(chunkKey)) return COLOR_BLOCKED;
                return COLOR_AVAILABLE;
            }

            @Override
            public ResourceLocation texture(long chunkKey) {
                return ChunkThumbnailClientState.getTexture(chunkKey);
            }
        });

        // menu.getBlockEntity() is the CLIENT's copy of the block entity; toggleChunk() only calls
        // setChanged() (not the block-update sync AbstractTimelineMachineBlockEntity's other
        // mutators trigger), so its additionalChunks set never actually reaches the client —
        // getChunkCount() would always read 1 here. ChronosphereClientState's synced selection is
        // the only client-accurate source.
        int claimedCount = ChronosphereClientState.getSelectedCount();
        int footerY = gridY + MAP_GRID.gridPixels() + MAP_FOOTER_GAP;
        drawCenteredNoShadow(guiGraphics, Component.literal(
                claimedCount + "/" + TOTAL_CLAIMABLE + " chunks claimed"),
                panelX() + CONTENT_SIZE / 2, footerY, TEXT_SECONDARY);
        int trackedCount = ChronosphereClientState.getTrackedCount();
        int trackedColor = trackedCount == claimedCount ? 0xFF2E8B45 : 0xFFB5701E;
        drawCenteredNoShadow(guiGraphics, Component.literal(
                trackedCount + "/" + claimedCount + " chunks tracked"),
                panelX() + CONTENT_SIZE / 2, footerY + MAP_FOOTER_LINE_SPACING, trackedColor);
        drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.chronosphere.map_hint"),
                panelX() + CONTENT_SIZE / 2, footerY + MAP_FOOTER_LINE_SPACING * 2, TEXT_MUTED);
    }

    private ChunkPos getGridCellAt(double mouseX, double mouseY) {
        return MAP_GRID.cellAt(mouseX, mouseY, gridX, gridY, new ChunkPos(menu.getBlockPos()));
    }

    private void renderTabs(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        renderBookmark(guiGraphics, mouseX, mouseY);
        renderAutoTrackTab(guiGraphics, mouseX, mouseY);
        renderSettingsTab(guiGraphics, mouseX, mouseY);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // Tabs are always drawn BEFORE the panel background, so the panel's opaque texture paints
        // over their small edge overlap and tucks them in — the same trick a vanilla recipe-book
        // tab uses to look attached to, rather than stacked on top of, its GUI. This holds
        // regardless of whether a modal overlay is open, so their z-order never shifts.
        renderTabs(guiGraphics, mouseX, mouseY);

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
            guiGraphics.renderTooltip(font, Component.literal(menu.getEnergyStored() + "/" + menu.getEnergyCapacity() + " FE"), mouseX, mouseY);
        } else if (isMouseOverEntropyBar(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, ChronovaultScreen.entropyTooltip(menu.getEntropy(), menu.getEntropyMax()), mouseX, mouseY);
        } else if (isMouseOverBookmark(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronosphere.map_tooltip"), mouseX, mouseY);
        } else if (isMouseOverAutoTrackTab(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, autoTrackTooltip(), mouseX, mouseY);
        } else if (isMouseOverSettingsTab(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.timeline_machine.settings_tooltip"), mouseX, mouseY);
        } else if (isMouseOverShowChangesButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()), mouseX, mouseY);
        } else if (isMouseOverJumpButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronovault.jump"), mouseX, mouseY);
        }
    }

    private static Component autoTrackTooltip() {
        return Component.translatable(ChronosphereClientState.isAutoTrackingEnabled()
                ? "gui.temporalindustries.timeline_machine.auto_track_tooltip_on"
                : "gui.temporalindustries.timeline_machine.auto_track_tooltip_off");
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        drawCenteredNoShadow(guiGraphics, Component.translatable("block.temporalindustries.chronosphere"), panelX() + CONTENT_SIZE / 2, panelY() + 8, 0xFF3F3F3F);

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
            PacketDistributor.sendToServer(new TimelineMachineToggleAutoTrackPacket(menu.getBlockPos(), newState));
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

        if (button == 0 && isMouseOverShowChangesButton(mouseX, mouseY)) {
            toggleShowChanges();
            return true;
        }

        if (button == 0 && isMouseOverJumpButton(mouseX, mouseY)) {
            jumpAndClose();
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

        if (button == 0 && graphWidget.mouseClicked(mouseX, mouseY, panelX() + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight())) {
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
        if (graphWidget.mouseScrolled(mouseX, mouseY, scrollY, panelX() + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight())) {
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
