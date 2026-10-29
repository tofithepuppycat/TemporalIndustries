package io.github.tofithepuppycat.temporalindustries.client.screen;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import org.lwjgl.glfw.GLFW;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.client.EnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.client.IconTabRenderer;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.client.window.ChronosphereMapWindow;
import io.github.tofithepuppycat.temporalindustries.client.window.TimelineSettingsWindow;
import io.github.tofithepuppycat.temporalindustries.client.window.WindowManager;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereBindRepositoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.RepositorySuggestRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineDeleteCommitPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineCommitPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereStateRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.RollbackChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
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
    private static final int CHARGE_BAR_HEIGHT = 4;
    private static final int CHARGE_BAR_GAP = 1;
    private static final int CHARGE_BAR_OVERHANG = 2;
    private static final ResourceLocation MENU_BUTTON_SMALL_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            io.github.tofithepuppycat.temporalindustries.TemporalIndustries.MODID, "textures/gui/menu_button_small.png");

    private static final int SYNC_INTERVAL_TICKS = 20;

    // Bookmark/settings tabs: a vertical stack overlapping the panel's right edge, like a vanilla
    // recipe-book tab.
    private static final int TAB_ROW_SIZE = IconTabRenderer.SIZE;
    private static final int TAB_OVERLAP = 5;
    private static final int BOOKMARK_Y_OFFSET = 40;
    private static final int TAB_ROW_SPACING = 4;
    private static final int SETTINGS_Y_OFFSET = BOOKMARK_Y_OFFSET + TAB_ROW_SIZE + TAB_ROW_SPACING;
    private static final int DELETE_BUTTON_HEIGHT = 20;
    private static final int BIND_TEXT_INSET = 20;
    private static final int COLOR_BORDER = 0xFF000000;

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
    /** The name last picked from the suggestions; no suggestions are shown while the box holds it. */
    private String acceptedName;

    private int ticksSinceSync = 0;
    private final WindowManager windows = new WindowManager();
    private final ChronosphereMapWindow mapWindow;
    private final TimelineSettingsWindow settingsWindow;

    private int bookmarkX;
    private int bookmarkY;
    private int settingsX;
    private int settingsY;
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
        mapWindow = new ChronosphereMapWindow(menu.getBlockPos());
        settingsWindow = new TimelineSettingsWindow(menu.getBlockPos(), menu::getBlockEntity, mapWindow::discardPendingToggles);
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
        if (!bound) {
            windows.closeAll();
        }
        nameBox.visible = !bound;
        nameBox.active = !bound;
        if (bound && !boundLastTick) {
            windows.openPinned(mapWindow, settingsWindow);
            requestTimelineView(true);
            PacketDistributor.sendToServer(new ChronosphereStateRequestPacket(menu.getBlockPos()));
        }
        boundLastTick = bound;
    }

    private void onNameChanged(String text) {
        suggestionIndex = -1;
        if (isBound()) return;
        if (text.equals(acceptedName)) return;
        acceptedName = null;
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
        if (isBound() || nameBox.getValue().equals(acceptedName)
                || !ChronosphereClientState.getSuggestionQuery().equals(nameBox.getValue())) return List.of();
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
        acceptedName = names.get(row);
        nameBox.setValue(acceptedName);
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
                panelX() + BIND_TEXT_INSET, panelY() + BIND_HINT_Y_OFFSET, CONTENT_SIZE - 2 * BIND_TEXT_INSET, TEXT_MUTED);

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
        windows.closeAll();
        graphWidget.onClose();
        super.onClose();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        TimelineProjectionManager.setCurrentGameTime(getCurrentGameTime());

        refreshBoundState();
        if (!boundLastTick) return;

        windows.tick();
        ticksSinceSync++;
        if (ticksSinceSync >= SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            requestTimelineView(false);
            PacketDistributor.sendToServer(new ChronosphereStateRequestPacket(menu.getBlockPos()));
        }
    }

    private void toggleShowChanges() {
        TimelineProjectionManager.toggleShowChanges();
    }

    private void jumpAndClose() {
        if (!TimelineProjectionManager.hasSelection()) {
            return;
        }
        long commitId = TimelineProjectionManager.getSelectedCommitId();
        boolean willCharge = TimelineProjectionManager.getJumpCost(commitId)
                .map(cost -> cost.total() > menu.getEnergyStored())
                .orElse(false);
        PacketDistributor.sendToServer(new RollbackChunkPacket(menu.getBlockPos(), commitId));
        if (willCharge) {
            return;
        }
        TimelineProjectionManager.clearSelectedCommit();
        TimelineProjectionManager.disableShowChanges();
        // Closing the windows first sends any claims the map still holds while the menu is open.
        windows.closeAll();
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
        InnerBackground.render(guiGraphics, panelX(), panelY(), CONTENT_SIZE, CONTENT_SIZE);
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        renderEnergyBar(guiGraphics);
        renderEntropyBar(guiGraphics);
        if (!isBound()) {
            renderBindPanel(guiGraphics, mouseX, mouseY);
            return;
        }
        graphWidget.render(guiGraphics, font, panelX() + GRAPH_X_OFFSET, graphTop(), GRAPH_WIDTH, graphHeight());
        renderActionButtons(guiGraphics, mouseX, mouseY);
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

        if (menu.isCharging()) {
            int barY = jumpY - CHARGE_BAR_HEIGHT - CHARGE_BAR_GAP;
            int barLeft = jumpX - CHARGE_BAR_OVERHANG;
            int barWidth = JUMP_BUTTON_WIDTH + 2 * CHARGE_BAR_OVERHANG;
            guiGraphics.fill(barLeft, barY, barLeft + barWidth, barY + CHARGE_BAR_HEIGHT, 0xFF000000);
            int filled = Math.round(menu.getChargeProgressPermille() / 1000.0F * (barWidth - 2));
            if (filled > 0) {
                guiGraphics.fill(barLeft + 1, barY + 1, barLeft + 1 + filled, barY + CHARGE_BAR_HEIGHT - 1, 0xFF3BFB98);
            }
        }

        boolean jumpActive = TimelineProjectionManager.hasSelection();
        int jumpTint = !jumpActive ? 0x80000000 : (isMouseOverJumpButton(mouseX, mouseY) ? 0x40FFFFFF : 0);
        guiGraphics.blit(MENU_BUTTON_SMALL_TEXTURE, jumpX, jumpY, JUMP_BUTTON_WIDTH, ACTION_BUTTON_SIZE,
                0.0F, 0.0F, JUMP_BUTTON_WIDTH, ACTION_BUTTON_SIZE, JUMP_BUTTON_WIDTH, ACTION_BUTTON_SIZE);
        if (jumpTint != 0) {
            guiGraphics.fill(jumpX, jumpY, jumpX + JUMP_BUTTON_WIDTH, jumpY + ACTION_BUTTON_SIZE, jumpTint);
        }
        guiGraphics.drawString(font, Component.translatable(menu.isCharging()
                        ? "gui.temporalindustries.timeline_view.charging_button"
                        : "gui.temporalindustries.chronovault.jump"),
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

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int rawMouseX, int rawMouseY, float partialTick) {
        // The panel beneath a window must not hover, tooltip or react to what the window covers.
        boolean covered = windows.isOverWindow(rawMouseX, rawMouseY);
        int mouseX = covered ? WindowManager.COVERED : rawMouseX;
        int mouseY = covered ? WindowManager.COVERED : rawMouseY;

        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // Tabs are drawn BEFORE the panel background so its opaque texture tucks in their overlap.
        if (isBound()) {
            renderBookmark(guiGraphics, mouseX, mouseY);
            renderSettingsTab(guiGraphics, mouseX, mouseY);
        }

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

        windows.render(guiGraphics, rawMouseX, rawMouseY);

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
            guiGraphics.renderTooltip(font, Component.translatable(menu.isCharging()
                    ? "gui.temporalindustries.timeline_view.charging_button"
                    : "gui.temporalindustries.chronovault.jump"), mouseX, mouseY);
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

        if (windows.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (button == 0 && isMouseOverBookmark(mouseX, mouseY)) {
            windows.toggle(mapWindow, panelX(), panelY());
            return true;
        }

        if (button == 0 && isMouseOverSettingsTab(mouseX, mouseY)) {
            windows.toggle(settingsWindow, panelX(), panelY());
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

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (windows.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        if (!isBound()) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (graphWidget.mouseDragged(dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (windows.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && graphWidget.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (windows.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (!isBound()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
