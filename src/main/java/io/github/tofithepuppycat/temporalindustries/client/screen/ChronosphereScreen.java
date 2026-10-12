package io.github.tofithepuppycat.temporalindustries.client.screen;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import org.lwjgl.glfw.GLFW;

import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.client.EnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.client.IconTabRenderer;
import io.github.tofithepuppycat.temporalindustries.client.ChunkThumbnailClientState;
import io.github.tofithepuppycat.temporalindustries.client.chunkmap.ChunkSelectionGrid;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereBindRepositoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereUnbindRepositoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.RepositorySuggestRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineDeleteCommitPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineDeleteHistoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineCommitPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereMapRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereStateRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereToggleChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.RollbackChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * GUI for the Chronosphere block: the same timeline graph view as the Chronovault (via {@link
 * TimelineGraphWidget}), except Jump moves every chunk this Chronosphere has claimed.
 */
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
    private static final ResourceLocation ICON_PUSH_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/icon_push.png");
    private static final ResourceLocation ICON_TRUE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/icon_true.png");
    private static final ResourceLocation ICON_FALSE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/icon_false.png");
    private static final ResourceLocation ICON_TRASHCAN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/icon_trashcan.png");
    private static final int ICON_CONFIG_SIZE = 26;

    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_HEIGHT = 256;
    /**
     * base.png's artwork sits inset this many pixels within the 256x256 canvas; layout constants
     * below are measured from the artwork's edge via {@link #panelX()}/{@link #panelY()}.
     */
    private static final int CONTENT_MARGIN = 16;
    private static final int CONTENT_SIZE = IMAGE_WIDTH - 2 * CONTENT_MARGIN;

    // base.png is a light panel, so text/UI accents are tuned for a light background.
    private static final int TEXT_PRIMARY = 0xFF2B2B2B;
    private static final int TEXT_SECONDARY = 0xFF5A5A5A;
    private static final int TEXT_MUTED = 0xFF7A7A7A;

    private static final int GRAPH_X_OFFSET = 8;
    private static final int GRAPH_Y_OFFSET = 18;
    private static final int GRAPH_WIDTH = CONTENT_SIZE - 2 * GRAPH_X_OFFSET;
    /**
     * Sized to leave room below for the preview-time labels and button row within CONTENT_SIZE.
     */
    private static final int GRAPH_HEIGHT = 154;

    private static final int ENERGY_BAR_X_OFFSET = 165;
    private static final int ENERGY_BAR_Y_OFFSET = 7;
    private static final int ENERGY_BAR_WIDTH = 52;
    private static final int ENERGY_BAR_HEIGHT = 8;

    private static final int ENTROPY_BAR_X_OFFSET = 6;
    private static final int ENTROPY_BAR_Y_OFFSET = 7;
    private static final int ENTROPY_BAR_WIDTH = 52;
    private static final int ENTROPY_BAR_HEIGHT = 8;

    private static final int PREVIEW_CURRENT_Y_OFFSET = 176;
    private static final int PREVIEW_DIFF_Y_OFFSET = 186;
    private static final int BUTTON_ROW_Y_OFFSET = 200;
    private static final int ACTION_BUTTON_SIZE = IconButtonRenderer.SIZE;
    private static final int ACTION_BUTTON_GAP = 2;
    /** Native width of menu_button_small.png (the wide Jump button). */
    private static final int JUMP_BUTTON_WIDTH = 64;
    private static final int JUMP_TEXT_PADDING = 5;
    private static final ResourceLocation MENU_BUTTON_SMALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/menu_button_small.png");

    private static final int SYNC_INTERVAL_TICKS = 20;
    /** How often the map overlay re-fetches terrain thumbnails while open. */
    private static final int MAP_SYNC_INTERVAL_TICKS = 100;

    // Bookmark/settings tabs: a vertical stack overlapping the panel's right edge, like a vanilla
    // recipe-book tab.
    private static final int TAB_ROW_SIZE = IconTabRenderer.SIZE;
    private static final int TAB_OVERLAP = 5;
    private static final int BOOKMARK_Y_OFFSET = 40;
    private static final int TAB_ROW_SPACING = 4;
    private static final int SETTINGS_Y_OFFSET = BOOKMARK_Y_OFFSET + TAB_ROW_SIZE + TAB_ROW_SPACING;
    private static final int DELETE_BUTTON_WIDTH = 140;
    private static final int DELETE_BUTTON_HEIGHT = 20;
    private static final int CONFIRM_BUTTON_WIDTH = 66;

    // Settings overlay layout: a title, then a "display" section and a "danger" section split by
    // dividers. Rows share one left/right inset.
    private static final int SETTINGS_INSET = 20;
    private static final int SETTINGS_TITLE_Y_OFFSET = 14;
    private static final int SETTINGS_TITLE_DIVIDER_Y_OFFSET = 28;
    private static final int GHOST_TOGGLE_ROW_Y_OFFSET = 40;
    private static final int GHOST_TOGGLE_HINT_Y_OFFSET = GHOST_TOGGLE_ROW_Y_OFFSET + IconButtonRenderer.SIZE + 4;
    private static final int SECTION_DIVIDER_Y_OFFSET = 100;
    private static final int CHANGE_REPOSITORY_BUTTON_Y_OFFSET = 108;
    private static final int DANGER_DIVIDER_Y_OFFSET = 134;
    private static final int DELETE_HISTORY_HINT_Y_OFFSET = 140;
    private static final int DELETE_HISTORY_BUTTON_Y_OFFSET = 186;
    private static final int COLOR_DIVIDER = 0x30000000;

    /**
     * 15px cells rather than the grid's 32px default, to fit the title and footer lines inside
     * CONTENT_SIZE.
     */
    private static final int MAP_CELL_SIZE = 15;
    private static final ChunkSelectionGrid MAP_GRID = new ChunkSelectionGrid(
            ChronosphereBlockEntity.MAX_RADIUS, ChronosphereBlockEntity.CLAIM_SHAPE, MAP_CELL_SIZE);
    private static final int TOTAL_CLAIMABLE = countClaimableCells();

    private static final int MAP_TITLE_Y_OFFSET = 6;
    private static final int MAP_GRID_Y_OFFSET = 18;
    private static final int MAP_FOOTER_GAP = 4;
    private static final int MAP_FOOTER_LINE_SPACING = 10;

    private static final int COLOR_HOME       = 0xFFFFD166;
    private static final int COLOR_SELECTED   = 0xFF3D9BE0;
    private static final int COLOR_BLOCKED    = 0xFFB55050;
    private static final int COLOR_AVAILABLE  = 0xFFAFAFAF;
    private static final int COLOR_BORDER     = 0xFF000000;

    // Name-entry panel shown while the Chronosphere has no repository.
    private static final int BIND_TITLE_Y_OFFSET = 56;
    private static final int BIND_HINT_Y_OFFSET = 72;
    private static final int BIND_BOX_Y_OFFSET = 112;
    private static final int BIND_BOX_WIDTH = 160;
    private static final int BIND_BOX_HEIGHT = 20;
    private static final int BIND_BUTTON_Y_OFFSET = 142;
    private static final int BIND_BUTTON_WIDTH = 100;
    private static final int SUGGEST_ROW_HEIGHT = 12;
    private static final int TITLE_HOVER_X_OFFSET = 60;
    private static final int TITLE_HOVER_WIDTH = 96;
    private static final int TITLE_HOVER_Y_OFFSET = 5;
    private static final int TITLE_HOVER_HEIGHT = 12;

    private final TimelineGraphWidget graphWidget = new TimelineGraphWidget();

    private EditBox nameBox;
    private boolean boundLastTick = false;
    /** The keyboard-highlighted repository suggestion, or -1 for none. */
    private int suggestionIndex = -1;

    private int ticksSinceSync = 0;
    private int ticksSinceMapSync = 0;
    private boolean mapOverlayOpen = false;
    private boolean settingsOverlayOpen = false;
    /**
     * Whether the settings overlay is showing the "are you sure" step; reset when the overlay
     * closes.
     */
    private boolean deleteHistoryConfirmPending = false;

    /**
     * Drag-to-select state for the claim map: the direction (claim/release) is fixed to whatever
     * the first cell of the drag did, so dragging a chosen direction across a mixed selection
     * doesn't flip cells back and forth.
     */
    private boolean mapDragActive = false;
    private boolean mapDragAdding = false;
    private final Set<Long> mapDragTouchedChunks = new HashSet<>();

    /**
     * Claims/releases made while the map overlay is open, held locally and only sent to the server
     * as one batch when the overlay closes (or the whole GUI closes), rather than one packet per
     * chunk touched.
     */
    private final Map<Long, Boolean> pendingChunkToggles = new LinkedHashMap<>();

    /**
     * Ctrl+drag alternative: rather than toggling cells as the cursor crosses them, the whole
     * rectangle between the press point and the release point is applied at once on release.
     */
    private boolean mapRectDragActive = false;
    private int mapRectStartDx;
    private int mapRectStartDz;
    private int mapRectHoverDx;
    private int mapRectHoverDz;
    private static final int COLOR_RECT_PREVIEW = 0x803D9BE0;

    /**
     * Zoom/pan for the map overlay: the viewport stays fixed at the unzoomed grid's footprint (so
     * layout around it doesn't move), while the grid itself scales up and pans within it.
     */
    private static final float MAP_MIN_ZOOM = 1.0F;
    private static final float MAP_MAX_ZOOM = 3.0F;
    private static final float MAP_ZOOM_STEP = 0.25F;
    private float mapZoom = MAP_MIN_ZOOM;
    private int mapPanX = 0;
    private int mapPanY = 0;
    /** Right-click pan state for the zoomed map. */
    private boolean mapPanDragActive = false;

    private int bookmarkX;
    private int bookmarkY;
    private int settingsX;
    private int settingsY;
    private int gridX;
    private int gridY;
    private int showChangesX;
    private int showChangesY;
    private int jumpX;
    private int jumpY;
    private int commitX;
    private int commitY;
    private int deleteCommitX;
    private int deleteCommitY;

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

    /**
     * The visible top-left corner of base.png's artwork; layout offsets are measured from here.
     */
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
        settingsX = panelX() + CONTENT_SIZE - TAB_OVERLAP;
        settingsY = panelY() + SETTINGS_Y_OFFSET;
        gridX = panelX() + (CONTENT_SIZE - MAP_GRID.gridPixels()) / 2;
        gridY = panelY() + MAP_GRID_Y_OFFSET;

        // Show Changes in the left corner with Commit right beside it, Delete Branch in the right
        // corner, and the wide Jump button centered.
        int rowLeft = panelX() + GRAPH_X_OFFSET;
        int rowRight = panelX() + CONTENT_SIZE - GRAPH_X_OFFSET - ACTION_BUTTON_SIZE;
        showChangesX = rowLeft;
        deleteCommitX = rowRight;
        commitX = rowLeft + ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP;
        jumpX = panelX() + (CONTENT_SIZE - JUMP_BUTTON_WIDTH) / 2;
        showChangesY = panelY() + BUTTON_ROW_Y_OFFSET;
        jumpY = showChangesY;
        commitY = showChangesY;
        deleteCommitY = showChangesY;

        TimelineProjectionManager.setActiveMachine(menu.getBlockPos());
        graphWidget.init(menu.getBlockPos());
        ChronosphereClientState.setActiveMachine(menu.getBlockPos());

        String previousName = nameBox == null ? "" : nameBox.getValue();
        nameBox = new EditBox(font, panelX() + (CONTENT_SIZE - BIND_BOX_WIDTH) / 2, panelY() + BIND_BOX_Y_OFFSET,
                BIND_BOX_WIDTH, BIND_BOX_HEIGHT, Component.translatable("gui.temporalindustries.chronosphere.bind_box"));
        nameBox.setMaxLength(TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH);
        nameBox.setHint(Component.translatable("gui.temporalindustries.chronosphere.bind_box"));
        nameBox.setValue(previousName);
        nameBox.setResponder(this::onNameChanged);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        // Fetches the timeline once the Chronosphere is bound: now if it already is, otherwise
        // when containerTick sees the binding arrive.
        boundLastTick = false;
        refreshBoundState();
    }

    /** Whether this Chronosphere has a repository yet, per the client's synced block entity. */
    private boolean isBound() {
        return menu.getBlockEntity().isRepositoryBound();
    }

    private void refreshBoundState() {
        boolean bound = isBound();
        if (!bound && boundLastTick) {
            // Suggestions cached while this block was unbound earlier may name a repository that
            // has since been deleted, so start the selection screen from a clean slate.
            ChronosphereClientState.clearSuggestions();
            nameBox.setValue("");
        }
        nameBox.visible = !bound;
        nameBox.active = !bound;
        if (bound && !boundLastTick) {
            requestTimelineView(true);
            PacketDistributor.sendToServer(new ChronosphereStateRequestPacket(menu.getBlockPos()));
        }
        boundLastTick = bound;
    }

    private void onNameChanged(String text) {
        suggestionIndex = -1;
        if (isBound()) return;
        if (text.trim().isEmpty()) {
            ChronosphereClientState.clearSuggestions();
            return;
        }
        PacketDistributor.sendToServer(new RepositorySuggestRequestPacket(menu.getBlockPos(), text));
    }

    /**
     * The server's suggestions for exactly what is currently typed, minus a lone suggestion that is
     * just the typed name again.
     */
    private List<String> visibleSuggestions() {
        if (isBound() || !ChronosphereClientState.getSuggestionQuery().equals(nameBox.getValue())) return List.of();
        List<String> names = ChronosphereClientState.getSuggestions();
        if (names.size() == 1 && names.get(0).equalsIgnoreCase(nameBox.getValue().trim())) return List.of();
        return names;
    }

    private int suggestionRowY(int row) {
        return nameBox.getY() + BIND_BOX_HEIGHT + row * SUGGEST_ROW_HEIGHT;
    }

    private int suggestionRowAt(double mouseX, double mouseY) {
        List<String> names = visibleSuggestions();
        for (int row = 0; row < names.size(); row++) {
            if (isMouseOverRect(mouseX, mouseY, nameBox.getX(), suggestionRowY(row), BIND_BOX_WIDTH, SUGGEST_ROW_HEIGHT)) return row;
        }
        return -1;
    }

    private void acceptSuggestion(int row) {
        List<String> names = visibleSuggestions();
        if (row < 0 || row >= names.size()) return;
        nameBox.setValue(names.get(row));
        nameBox.setFocused(true);
    }

    private void renderSuggestions(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<String> names = visibleSuggestions();
        if (names.isEmpty()) return;

        int hoveredRow = suggestionRowAt(mouseX, mouseY);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 200);
        int x = nameBox.getX();
        guiGraphics.fill(x - 1, suggestionRowY(0), x + BIND_BOX_WIDTH + 1, suggestionRowY(names.size()) + 1, COLOR_BORDER);
        for (int row = 0; row < names.size(); row++) {
            int y = suggestionRowY(row);
            boolean highlighted = row == hoveredRow || row == suggestionIndex;
            guiGraphics.fill(x, y, x + BIND_BOX_WIDTH, y + SUGGEST_ROW_HEIGHT, highlighted ? 0xFF3A3A5A : 0xFF202020);
            guiGraphics.drawString(font, names.get(row), x + 3, y + (SUGGEST_ROW_HEIGHT - font.lineHeight) / 2 + 1,
                    highlighted ? 0xFFFFFFFF : 0xFFAAAAAA, false);
        }
        guiGraphics.pose().popPose();
    }

    private void submitBind() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) return;
        PacketDistributor.sendToServer(new ChronosphereBindRepositoryPacket(menu.getBlockPos(), name));
    }

    private int bindButtonX() {
        return panelX() + (CONTENT_SIZE - BIND_BUTTON_WIDTH) / 2;
    }

    private int bindButtonY() {
        return panelY() + BIND_BUTTON_Y_OFFSET;
    }

    private void renderBindPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.chronosphere.bind_title"),
                panelX() + CONTENT_SIZE / 2, panelY() + BIND_TITLE_Y_OFFSET, TEXT_PRIMARY);
        drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.chronosphere.bind_hint"),
                panelX() + SETTINGS_INSET, panelY() + BIND_HINT_Y_OFFSET, CONTENT_SIZE - 2 * SETTINGS_INSET, TEXT_MUTED);

        boolean canSubmit = !nameBox.getValue().trim().isEmpty();
        int baseColor = canSubmit ? 0xFF3A6A3A : 0xFF555555;
        boolean hovered = canSubmit && isMouseOverRect(mouseX, mouseY, bindButtonX(), bindButtonY(), BIND_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT);
        drawOverlayButton(guiGraphics, bindButtonX(), bindButtonY(), BIND_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                Component.translatable("gui.temporalindustries.chronosphere.bind_button"), baseColor, hovered);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isBound()) {
            int suggestionCount = visibleSuggestions().size();
            if (suggestionCount > 0) {
                if (keyCode == GLFW.GLFW_KEY_DOWN) {
                    suggestionIndex = (suggestionIndex + 1) % suggestionCount;
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_UP) {
                    suggestionIndex = (suggestionIndex <= 0 ? suggestionCount : suggestionIndex) - 1;
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_TAB) {
                    acceptSuggestion(Math.max(suggestionIndex, 0));
                    return true;
                }
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (suggestionIndex >= 0 && suggestionIndex < suggestionCount) {
                    acceptSuggestion(suggestionIndex);
                } else {
                    submitBind();
                }
                return true;
            }
            // Typing 'e' (the inventory key) must edit the name rather than close the screen.
            if (keyCode != GLFW.GLFW_KEY_ESCAPE && (nameBox.keyPressed(keyCode, scanCode, modifiers) || nameBox.canConsumeInput())) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Requests the repository's commit graph plus the preview for the current selection. forceFull
     * bypasses the server's "nothing changed" skip, needed right after opening since a stale cached
     * head id could otherwise coincidentally match and cause the server to wrongly skip replying.
     */
    private void requestTimelineView(boolean forceFull) {
        long lastKnownHead = forceFull ? Long.MIN_VALUE : TimelineProjectionManager.getHeadCommitId();
        long lastKnownPreviewVersion = forceFull ? Long.MIN_VALUE : TimelineProjectionManager.getPreviewVersion();
        PacketDistributor.sendToServer(new TimelinePreviewRequestPacket(
                menu.getBlockPos(), lastKnownHead, lastKnownPreviewVersion,
                TimelineProjectionManager.getSelectedCommitId()));
    }

    @Override
    public void onClose() {
        flushPendingChunkToggles();
        graphWidget.onClose();
        ChunkThumbnailClientState.clearAll();
        super.onClose();
    }

    /**
     * The claim map's selected/blocked view, as if pendingChunkToggles had already been applied;
     * used so dragging reflects instantly without waiting on a server round trip.
     */
    private Set<Long> effectiveSelectedChunks() {
        Set<Long> effective = new HashSet<>(ChronosphereClientState.getSelectedChunks());
        for (Map.Entry<Long, Boolean> entry : pendingChunkToggles.entrySet()) {
            if (entry.getValue()) effective.add(entry.getKey());
            else effective.remove(entry.getKey());
        }
        return effective;
    }

    /**
     * Sends every pending claim/release as one batch packet and clears them; a no-op if nothing was
     * touched this visit.
     */
    private void flushPendingChunkToggles() {
        if (pendingChunkToggles.isEmpty()) return;
        PacketDistributor.sendToServer(ChronosphereToggleChunkPacket.fromPending(menu.getBlockPos(), pendingChunkToggles));
        pendingChunkToggles.clear();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        TimelineProjectionManager.setCurrentGameTime(getCurrentGameTime());

        refreshBoundState();
        if (!boundLastTick) return;

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

    /**
     * Mirrors the button rects renderSettingsOverlay draws, since they're plain fills rather than
     * Button widgets.
     */
    private void handleSettingsOverlayClick(double mouseX, double mouseY) {
        int buttonY = panelY() + DELETE_HISTORY_BUTTON_Y_OFFSET;

        if (isMouseOverRect(mouseX, mouseY, ghostToggleButtonX(), ghostToggleButtonY(),
                IconButtonRenderer.SIZE, IconButtonRenderer.SIZE)) {
            TimelineProjectionManager.toggleGhostBlockMode();
            return;
        }

        if (isMouseOverRect(mouseX, mouseY, changeRepositoryButtonX(), changeRepositoryButtonY(),
                DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
            changeRepository();
            return;
        }

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

    private int changeRepositoryButtonX() {
        return panelX() + (CONTENT_SIZE - DELETE_BUTTON_WIDTH) / 2;
    }

    private int changeRepositoryButtonY() {
        return panelY() + CHANGE_REPOSITORY_BUTTON_Y_OFFSET;
    }

    /** Detaches from the current repository and shows the name-entry panel again. */
    private void changeRepository() {
        pendingChunkToggles.clear();
        PacketDistributor.sendToServer(new ChronosphereUnbindRepositoryPacket(menu.getBlockPos()));
        settingsOverlayOpen = false;
        deleteHistoryConfirmPending = false;
        TimelineProjectionManager.clearSelectedCommit();
    }

    private void jumpAndClose() {
        if (!TimelineProjectionManager.hasSelection()) {
            return;
        }
        PacketDistributor.sendToServer(new RollbackChunkPacket(menu.getBlockPos(),
                TimelineProjectionManager.getSelectedCommitId()));
        TimelineProjectionManager.clearSelectedCommit();
        TimelineProjectionManager.disableShowChanges();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    /** Deletes the selected commit and everything descended from it. */
    private void deleteSelectedCommit() {
        if (!TimelineProjectionManager.isSelectedCommitDeletable()) {
            return;
        }
        PacketDistributor.sendToServer(new TimelineMachineDeleteCommitPacket(menu.getBlockPos(),
                TimelineProjectionManager.getSelectedCommitId()));
        TimelineProjectionManager.clearSelectedCommit();
        // Deleting a commit doesn't move any chunk's head, so the server's "nothing changed" skip
        // would otherwise suppress a reply; force a full resync to pick up the removed commits.
        requestTimelineView(true);
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
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        renderEnergyBar(guiGraphics);
        renderEntropyBar(guiGraphics);
        if (!isBound()) {
            renderBindPanel(guiGraphics, mouseX, mouseY);
            return;
        }
        graphWidget.render(guiGraphics, font, panelX() + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight());

        if (!mapOverlayOpen && !settingsOverlayOpen) {
            renderActionButtons(guiGraphics, mouseX, mouseY);
        }
    }

    /**
     * Show Changes / Jump / Commit: icon buttons; Show Changes tints on while active, Jump dims
     * while nothing is selected.
     */
    private void renderActionButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        boolean showChangesEnabled = TimelineProjectionManager.isShowChangesEnabled();
        int showChangesTint = showChangesEnabled ? 0xB0CC5555 : (isMouseOverShowChangesButton(mouseX, mouseY) ? 0x40000000 : 0);
        IconButtonRenderer.renderBackground(guiGraphics, showChangesX, showChangesY, showChangesTint);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_EYE_TEXTURE, showChangesX, showChangesY);

        boolean jumpActive = TimelineProjectionManager.hasSelection();
        int jumpTint = !jumpActive ? 0x80000000 : (isMouseOverJumpButton(mouseX, mouseY) ? 0x40FFFFFF : 0);
        guiGraphics.blit(MENU_BUTTON_SMALL_TEXTURE, jumpX, jumpY, JUMP_BUTTON_WIDTH, ACTION_BUTTON_SIZE,
                0.0F, 0.0F, JUMP_BUTTON_WIDTH, ACTION_BUTTON_SIZE, JUMP_BUTTON_WIDTH, ACTION_BUTTON_SIZE);
        if (jumpTint != 0) {
            guiGraphics.fill(jumpX, jumpY, jumpX + JUMP_BUTTON_WIDTH, jumpY + ACTION_BUTTON_SIZE, jumpTint);
        }
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chronovault.jump"),
                jumpX + JUMP_TEXT_PADDING, jumpY + (ACTION_BUTTON_SIZE - font.lineHeight) / 2 + 1, TEXT_PRIMARY, false);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_JUMP_TEXTURE, jumpX + JUMP_BUTTON_WIDTH - ACTION_BUTTON_SIZE, jumpY);

        int commitTint = isMouseOverCommitButton(mouseX, mouseY) ? 0x40FFFFFF : 0;
        IconButtonRenderer.renderBackground(guiGraphics, commitX, commitY, commitTint);
        IconButtonRenderer.renderIcon(guiGraphics, ICON_PUSH_TEXTURE, commitX, commitY);

        // Hidden entirely until a deletable commit is selected.
        if (TimelineProjectionManager.isSelectedCommitDeletable()) {
            int deleteTint = isMouseOverDeleteCommitButton(mouseX, mouseY) ? 0x40FFFFFF : 0;
            IconButtonRenderer.renderBackground(guiGraphics, deleteCommitX, deleteCommitY, deleteTint);
            IconButtonRenderer.renderIcon(guiGraphics, ICON_TRASHCAN_TEXTURE, deleteCommitX, deleteCommitY);
        }
    }

    private boolean isMouseOverShowChangesButton(double mouseX, double mouseY) {
        return mouseX >= showChangesX && mouseX <= showChangesX + ACTION_BUTTON_SIZE
                && mouseY >= showChangesY && mouseY <= showChangesY + ACTION_BUTTON_SIZE;
    }

    private boolean isMouseOverJumpButton(double mouseX, double mouseY) {
        return mouseX >= jumpX && mouseX <= jumpX + JUMP_BUTTON_WIDTH
                && mouseY >= jumpY && mouseY <= jumpY + ACTION_BUTTON_SIZE;
    }

    private boolean isMouseOverCommitButton(double mouseX, double mouseY) {
        return mouseX >= commitX && mouseX <= commitX + ACTION_BUTTON_SIZE
                && mouseY >= commitY && mouseY <= commitY + ACTION_BUTTON_SIZE;
    }

    private boolean isMouseOverDeleteCommitButton(double mouseX, double mouseY) {
        return TimelineProjectionManager.isSelectedCommitDeletable()
                && mouseX >= deleteCommitX && mouseX <= deleteCommitX + ACTION_BUTTON_SIZE
                && mouseY >= deleteCommitY && mouseY <= deleteCommitY + ACTION_BUTTON_SIZE;
    }

    private int graphTop() {
        return panelY() + GRAPH_Y_OFFSET;
    }

    private int graphHeight() {
        return GRAPH_HEIGHT;
    }

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

    /** Bidirectional order/chaos balance bar: fills from the center tick outward. */
    private void renderEntropyBar(GuiGraphics guiGraphics) {
        int barX = panelX() + ENTROPY_BAR_X_OFFSET;
        int barY = panelY() + ENTROPY_BAR_Y_OFFSET;
        int entropy = menu.getEntropy();
        int max = menu.getEntropyMax();

        float balance = max > 0 ? (entropy - max / 2f) / (max / 2f) : 0f; // -1 (order) .. +1 (chaos)
        EntropyBalanceBar.render(guiGraphics, barX, barY, ENTROPY_BAR_WIDTH, ENTROPY_BAR_HEIGHT, balance);
    }

    private boolean isMouseOverEntropyBar(int mouseX, int mouseY) {
        int barX = panelX() + ENTROPY_BAR_X_OFFSET;
        int barY = panelY() + ENTROPY_BAR_Y_OFFSET;
        return mouseX >= barX && mouseX <= barX + ENTROPY_BAR_WIDTH && mouseY >= barY && mouseY <= barY + ENTROPY_BAR_HEIGHT;
    }

    private void renderBookmark(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        IconTabRenderer.renderBackground(guiGraphics, bookmarkX, bookmarkY, 0);

        // Placeholder icon: a 3x3 grid glyph, echoing the claim map it opens.
        int glyphColor = TEXT_PRIMARY;
        int cell = 4;
        int gap = 1;
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

    private void renderSettingsTab(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        IconTabRenderer.renderBackground(guiGraphics, settingsX, settingsY, 0);
        IconTabRenderer.renderIcon(guiGraphics, ICON_CONFIG_TEXTURE, ICON_CONFIG_SIZE, settingsX, settingsY);
    }

    private boolean isMouseOverSettingsTab(double mouseX, double mouseY) {
        return mouseX >= settingsX && mouseX <= settingsX + TAB_ROW_SIZE
                && mouseY >= settingsY && mouseY <= settingsY + TAB_ROW_SIZE;
    }

    private void renderSettingsOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        int textX = panelX() + SETTINGS_INSET;
        int textWidth = CONTENT_SIZE - 2 * SETTINGS_INSET;

        drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.settings_title"),
                panelX() + CONTENT_SIZE / 2, panelY() + SETTINGS_TITLE_Y_OFFSET, TEXT_PRIMARY);
        drawDivider(guiGraphics, panelY() + SETTINGS_TITLE_DIVIDER_Y_OFFSET);

        boolean ghostEnabled = TimelineProjectionManager.isGhostBlockModeEnabled();
        // Label on the left, two-layer icon toggle (background + true/false icon) on the right.
        int ghostRowX = textX;
        int ghostButtonX = ghostToggleButtonX();
        int ghostButtonY = ghostToggleButtonY();
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.timeline_machine.ghost_preview_label"),
                ghostRowX, ghostButtonY + (IconButtonRenderer.SIZE - 8) / 2, TEXT_PRIMARY, false);
        int ghostTint = isMouseOverRect(mouseX, mouseY, ghostButtonX, ghostButtonY,
                IconButtonRenderer.SIZE, IconButtonRenderer.SIZE) ? 0x40000000 : 0;
        IconButtonRenderer.renderBackground(guiGraphics, ghostButtonX, ghostButtonY, ghostTint);
        IconButtonRenderer.renderIcon(guiGraphics, ghostEnabled ? ICON_TRUE_TEXTURE : ICON_FALSE_TEXTURE,
                ghostButtonX, ghostButtonY);
        drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.ghost_preview_hint",
                        TimelineProjectionManager.GHOST_BLOCK_RENDER_LIMIT),
                textX, panelY() + GHOST_TOGGLE_HINT_Y_OFFSET, textWidth, TEXT_MUTED);

        drawDivider(guiGraphics, panelY() + SECTION_DIVIDER_Y_OFFSET);

        drawOverlayButton(guiGraphics, changeRepositoryButtonX(), changeRepositoryButtonY(),
                DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                Component.translatable("gui.temporalindustries.chronosphere.change_repository_button"),
                0xFF3A5A8A, isMouseOverRect(mouseX, mouseY, changeRepositoryButtonX(), changeRepositoryButtonY(),
                        DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));

        drawDivider(guiGraphics, panelY() + DANGER_DIVIDER_Y_OFFSET);

        drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.delete_history_hint"),
                textX, panelY() + DELETE_HISTORY_HINT_Y_OFFSET, textWidth, TEXT_MUTED);

        int buttonX = panelX() + (CONTENT_SIZE - DELETE_BUTTON_WIDTH) / 2;
        int buttonY = panelY() + DELETE_HISTORY_BUTTON_Y_OFFSET;

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

    private void drawDivider(GuiGraphics guiGraphics, int y) {
        guiGraphics.fill(panelX() + SETTINGS_INSET, y, panelX() + CONTENT_SIZE - SETTINGS_INSET, y + 1, COLOR_DIVIDER);
    }

    private int ghostToggleButtonX() {
        return panelX() + CONTENT_SIZE - SETTINGS_INSET - IconButtonRenderer.SIZE;
    }

    private int ghostToggleButtonY() {
        return panelY() + GHOST_TOGGLE_ROW_Y_OFFSET;
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

    // No no-shadow overload exists on GuiGraphics#drawCenteredString/#drawWordWrap; against this
    // light panel the shadow reads as ghosting, so these draw text without one.
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

    private void renderMapOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.chronosphere.map_title"),
                panelX() + CONTENT_SIZE / 2, panelY() + MAP_TITLE_Y_OFFSET, TEXT_PRIMARY);

        ChunkPos home = new ChunkPos(menu.getBlockPos());
        Set<Long> effectiveSelected = effectiveSelectedChunks();

        int viewportSize = mapViewportSize();
        guiGraphics.enableScissor(gridX, gridY, gridX + viewportSize, gridY + viewportSize);
        MAP_GRID.render(guiGraphics, zoomedGridX(), zoomedGridY(), mapZoom, home, new ChunkSelectionGrid.CellPainter() {
            @Override
            public int tint(int dx, int dz, long chunkKey) {
                if (dx == 0 && dz == 0) return COLOR_HOME;
                if (effectiveSelected.contains(chunkKey)) return COLOR_SELECTED;
                if (ChronosphereClientState.isBlocked(chunkKey)) return COLOR_BLOCKED;
                return COLOR_AVAILABLE;
            }

            @Override
            public ResourceLocation texture(long chunkKey) {
                return ChunkThumbnailClientState.getTexture(chunkKey);
            }

            @Override
            public boolean emphasized(int dx, int dz, long chunkKey) {
                return (dx == 0 && dz == 0) || effectiveSelected.contains(chunkKey);
            }
        });

        if (isMouseOverRect(mouseX, mouseY, gridX, gridY, viewportSize, viewportSize)) {
            MAP_GRID.renderHover(guiGraphics, zoomedGridX(), zoomedGridY(), mapZoom, mouseX, mouseY);
        }

        if (mapRectDragActive) {
            MAP_GRID.renderRectHighlight(guiGraphics, zoomedGridX(), zoomedGridY(), mapZoom,
                    mapRectStartDx, mapRectStartDz, mapRectHoverDx, mapRectHoverDz, COLOR_RECT_PREVIEW);
        }
        guiGraphics.disableScissor();
        MAP_GRID.renderFrame(guiGraphics, gridX, gridY, viewportSize);

        // menu.getBlockEntity() is the client's stale copy (toggleChunk() only calls setChanged(),
        // not a synced mutator), so ChronosphereClientState (plus this visit's pending toggles) is
        // the only client-accurate source.
        int selectedCount = effectiveSelected.size();
        int footerY = gridY + viewportSize + MAP_FOOTER_GAP;
        drawCenteredNoShadow(guiGraphics, Component.literal(
                selectedCount + "/" + TOTAL_CLAIMABLE + " chunks selected"),
                panelX() + CONTENT_SIZE / 2, footerY, TEXT_SECONDARY);
        int capturedCount = ChronosphereClientState.getCapturedCount();
        int capturedColor = capturedCount == selectedCount ? 0xFF2E8B45 : 0xFFB5701E;
        drawCenteredNoShadow(guiGraphics, Component.literal(
                capturedCount + "/" + selectedCount + " chunks captured"),
                panelX() + CONTENT_SIZE / 2, footerY + MAP_FOOTER_LINE_SPACING, capturedColor);
        drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.chronosphere.map_hint"),
                panelX() + CONTENT_SIZE / 2, footerY + MAP_FOOTER_LINE_SPACING * 2, TEXT_MUTED);
    }

    /**
     * The map viewport's fixed on-screen footprint; stays the unzoomed grid size so the title/
     * footer layout around it never moves, while the grid itself scales and pans within it.
     */
    private int mapViewportSize() {
        return MAP_GRID.gridPixels();
    }

    private int mapMaxPanX() {
        return Math.max(0, MAP_GRID.gridPixels(mapZoom) - mapViewportSize());
    }

    private int mapMaxPanY() {
        return mapMaxPanX(); // grid is square
    }

    private void clampMapPan() {
        mapPanX = Math.min(Math.max(mapPanX, 0), mapMaxPanX());
        mapPanY = Math.min(Math.max(mapPanY, 0), mapMaxPanY());
    }

    /**
     * The scaled grid's top-left, after panning; feeds both rendering and hit-testing so they
     * agree.
     */
    private int zoomedGridX() {
        return gridX - mapPanX;
    }

    private int zoomedGridY() {
        return gridY - mapPanY;
    }

    private void resetMapZoom() {
        mapZoom = MAP_MIN_ZOOM;
        mapPanX = 0;
        mapPanY = 0;
    }

    private void adjustMapZoom(double scrollAmount, double mouseX, double mouseY) {
        float oldZoom = mapZoom;
        float newZoom = (float) Math.min(MAP_MAX_ZOOM, Math.max(MAP_MIN_ZOOM,
                mapZoom + Math.signum(scrollAmount) * MAP_ZOOM_STEP));
        if (newZoom == oldZoom) return;

        // Keep the chunk under the cursor stationary across the zoom change, so scrolling in
        // feels like zooming toward the pointer rather than toward the viewport's corner.
        double anchorXInGrid = mouseX - zoomedGridX();
        double anchorYInGrid = mouseY - zoomedGridY();
        double scale = newZoom / oldZoom;
        mapZoom = newZoom;
        mapPanX = (int) Math.round(mapPanX + anchorXInGrid * (scale - 1));
        mapPanY = (int) Math.round(mapPanY + anchorYInGrid * (scale - 1));
        clampMapPan();
    }

    private ChunkPos getGridCellAt(double mouseX, double mouseY) {
        return MAP_GRID.cellAt(mouseX, mouseY, zoomedGridX(), zoomedGridY(), mapZoom, new ChunkPos(menu.getBlockPos()));
    }

    /** Draws the tabs whose overlay is not the open one (all of them when none is open). */
    private void renderInactiveTabs(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!mapOverlayOpen) renderBookmark(guiGraphics, mouseX, mouseY);
        if (mapOverlayOpen || !settingsOverlayOpen) renderSettingsTab(guiGraphics, mouseX, mouseY);
    }

    /** Draws the open overlay's tab, if any, on top of the overlay. */
    private void renderActiveTab(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (mapOverlayOpen) {
            renderBookmark(guiGraphics, mouseX, mouseY);
        } else if (settingsOverlayOpen) {
            renderSettingsTab(guiGraphics, mouseX, mouseY);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // Tabs are drawn BEFORE the panel background so its opaque texture tucks in their overlap.
        // The tab of the open overlay is drawn after it instead, so it sits on top.
        if (isBound()) renderInactiveTabs(guiGraphics, mouseX, mouseY);

        renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderLabels(guiGraphics, mouseX, mouseY);
        for (var widget : renderables) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (!isBound()) {
            renderSuggestions(guiGraphics, mouseX, mouseY);
            if (isMouseOverEnergyBar(mouseX, mouseY)) {
                guiGraphics.renderTooltip(font, Component.literal(EnergyDisplay.format(menu.getEnergyStored()) + "/" + EnergyDisplay.format(menu.getEnergyCapacity()) + " FE"), mouseX, mouseY);
            } else if (isMouseOverEntropyBar(mouseX, mouseY)) {
                guiGraphics.renderTooltip(font, ChronovaultScreen.entropyTooltip(menu.getEntropy(), menu.getEntropyMax()), mouseX, mouseY);
            }
            return;
        }

        if (mapOverlayOpen) {
            renderMapOverlay(guiGraphics, mouseX, mouseY);
        } else if (settingsOverlayOpen) {
            renderSettingsOverlay(guiGraphics, mouseX, mouseY);
        }
        renderActiveTab(guiGraphics, mouseX, mouseY);

        if (mapOverlayOpen) {
            if (isMouseOverBookmark(mouseX, mouseY)) {
                guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronosphere.map_tooltip"), mouseX, mouseY);
            }
            return;
        }
        if (settingsOverlayOpen) {
            return;
        }

        List<FormattedCharSequence> tooltip = graphWidget.getTooltipAt(mouseX, mouseY);
        if (!tooltip.isEmpty()) {
            guiGraphics.renderTooltip(font, tooltip, mouseX, mouseY);
        } else if (isMouseOverEnergyBar(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.literal(EnergyDisplay.format(menu.getEnergyStored()) + "/" + EnergyDisplay.format(menu.getEnergyCapacity()) + " FE"), mouseX, mouseY);
        } else if (isMouseOverEntropyBar(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, ChronovaultScreen.entropyTooltip(menu.getEntropy(), menu.getEntropyMax()), mouseX, mouseY);
        } else if (isMouseOverRect(mouseX, mouseY, panelX() + TITLE_HOVER_X_OFFSET, panelY() + TITLE_HOVER_Y_OFFSET,
                TITLE_HOVER_WIDTH, TITLE_HOVER_HEIGHT)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronosphere.repository_tooltip",
                    menu.getBlockEntity().getRepositoryName()), mouseX, mouseY);
        } else if (isMouseOverBookmark(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronosphere.map_tooltip"), mouseX, mouseY);
        } else if (isMouseOverSettingsTab(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.timeline_machine.settings_tooltip"), mouseX, mouseY);
        } else if (isMouseOverShowChangesButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()), mouseX, mouseY);
        } else if (isMouseOverJumpButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.chronovault.jump"), mouseX, mouseY);
        } else if (isMouseOverCommitButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.timeline_machine.commit_tooltip"), mouseX, mouseY);
        } else if (isMouseOverDeleteCommitButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, ChronovaultScreen.deleteCommitTooltip(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        drawCenteredNoShadow(guiGraphics, Component.translatable("block.temporalindustries.chronosphere"), panelX() + CONTENT_SIZE / 2, panelY() + 8, 0xFF3F3F3F);
        if (!isBound()) return;

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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isBound()) {
            int suggestionRow = button == 0 ? suggestionRowAt(mouseX, mouseY) : -1;
            if (suggestionRow >= 0) {
                acceptSuggestion(suggestionRow);
                return true;
            }
            if (button == 0 && !nameBox.getValue().trim().isEmpty()
                    && isMouseOverRect(mouseX, mouseY, bindButtonX(), bindButtonY(), BIND_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                submitBind();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (button == 0 && isMouseOverBookmark(mouseX, mouseY)) {
            if (mapOverlayOpen) {
                flushPendingChunkToggles();
            }
            mapOverlayOpen = !mapOverlayOpen;
            settingsOverlayOpen = false;
            deleteHistoryConfirmPending = false;
            mapDragActive = false;
            mapDragTouchedChunks.clear();
            mapRectDragActive = false;
            mapPanDragActive = false;
            resetMapZoom();
            if (mapOverlayOpen) {
                ticksSinceMapSync = 0;
                PacketDistributor.sendToServer(new ChronosphereMapRequestPacket(menu.getBlockPos()));
            }
            return true;
        }

        if (button == 0 && isMouseOverSettingsTab(mouseX, mouseY)) {
            if (mapOverlayOpen) {
                flushPendingChunkToggles();
            }
            settingsOverlayOpen = !settingsOverlayOpen;
            mapOverlayOpen = false;
            deleteHistoryConfirmPending = false;
            return true;
        }

        if (mapOverlayOpen) {
            if (button == 0) {
                if (hasControlDown()) {
                    int[] start = MAP_GRID.clampedOffsetAt(mouseX, mouseY, zoomedGridX(), zoomedGridY(), mapZoom);
                    mapRectStartDx = mapRectHoverDx = start[0];
                    mapRectStartDz = mapRectHoverDz = start[1];
                    mapRectDragActive = true;
                    mapDragTouchedChunks.clear();
                    ChunkPos home = new ChunkPos(menu.getBlockPos());
                    long startKey = new ChunkPos(home.x + start[0], home.z + start[1]).toLong();
                    mapDragAdding = !effectiveSelectedChunks().contains(startKey);
                } else {
                    ChunkPos clicked = getGridCellAt(mouseX, mouseY);
                    if (clicked != null && !clicked.equals(new ChunkPos(menu.getBlockPos()))) {
                        long key = clicked.toLong();
                        mapDragActive = true;
                        mapDragAdding = !effectiveSelectedChunks().contains(key);
                        mapDragTouchedChunks.clear();
                        applyMapDragToggle(key);
                    }
                }
            } else if (button == 1 && mapZoom > MAP_MIN_ZOOM) {
                mapPanDragActive = true;
            }
            // Modal: swallow clicks so nothing underneath reacts to them.
            return true;
        }

        if (settingsOverlayOpen) {
            if (button == 0) {
                handleSettingsOverlayClick(mouseX, mouseY);
            }
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

        if (button == 0 && isMouseOverCommitButton(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new TimelineMachineCommitPacket(menu.getBlockPos()));
            return true;
        }

        if (button == 0 && isMouseOverDeleteCommitButton(mouseX, mouseY)) {
            deleteSelectedCommit();
            return true;
        }

        if (button == 0 && graphWidget.mouseClicked(mouseX, mouseY, panelX() + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight())) {
            // The ghost preview is resolved server-side per selected commit, so ask for the new
            // one right away instead of letting it lag until the next periodic poll.
            requestTimelineView(false);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Records {@code key}'s claim/release using the drag's fixed direction, once per cell per drag,
     * skipping chunks blocked by another machine when claiming.
     */
    private void applyMapDragToggle(long key) {
        if (!mapDragTouchedChunks.add(key)) return;
        if (mapDragAdding && ChronosphereClientState.isBlocked(key)) return;
        pendingChunkToggles.put(key, mapDragAdding);
    }

    /**
     * Applies the rectangle between the press point and the release point, in the direction fixed
     * by the starting cell, once mapRectDragActive drag ends.
     */
    private void applyRectDrag() {
        ChunkPos home = new ChunkPos(menu.getBlockPos());
        int minDx = Math.min(mapRectStartDx, mapRectHoverDx);
        int maxDx = Math.max(mapRectStartDx, mapRectHoverDx);
        int minDz = Math.min(mapRectStartDz, mapRectHoverDz);
        int maxDz = Math.max(mapRectStartDz, mapRectHoverDz);
        for (int dx = minDx; dx <= maxDx; dx++) {
            for (int dz = minDz; dz <= maxDz; dz++) {
                if (dx == 0 && dz == 0) continue; // home chunk isn't toggleable
                if (!MAP_GRID.isWithinRadius(dx, dz)) continue;
                applyMapDragToggle(new ChunkPos(home.x + dx, home.z + dz).toLong());
            }
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!isBound()) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (mapOverlayOpen) {
            if (button == 1 && mapPanDragActive) {
                mapPanX -= (int) Math.round(dragX);
                mapPanY -= (int) Math.round(dragY);
                clampMapPan();
            } else if (button == 0 && mapRectDragActive) {
                int[] hover = MAP_GRID.clampedOffsetAt(mouseX, mouseY, zoomedGridX(), zoomedGridY(), mapZoom);
                mapRectHoverDx = hover[0];
                mapRectHoverDz = hover[1];
            } else if (button == 0 && mapDragActive) {
                ChunkPos hovered = getGridCellAt(mouseX, mouseY);
                if (hovered != null && !hovered.equals(new ChunkPos(menu.getBlockPos()))) {
                    applyMapDragToggle(hovered.toLong());
                }
            }
            return true;
        }
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
        if (mapOverlayOpen) {
            if (mapRectDragActive) {
                applyRectDrag();
                mapRectDragActive = false;
            }
            mapDragActive = false;
            mapDragTouchedChunks.clear();
            mapPanDragActive = false;
            return true;
        }
        if (button == 0 && graphWidget.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isBound()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (mapOverlayOpen) {
            adjustMapZoom(scrollY, mouseX, mouseY);
            return true;
        }
        if (graphWidget.mouseScrolled(mouseX, mouseY, scrollY, panelX() + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight())) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
