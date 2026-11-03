package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteRepositoryPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static io.github.tofithepuppycat.temporalindustries.client.screen.RemoteUi.*;

/**
 * Repository picker of the Chrono Remote Control: names the repository the linked machine should
 * use instead, with the free repositories that match the typed text offered to pick from. A name
 * that doesn't exist yet creates a new repository.
 */
public class ChronoRemoteRepositoryScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int PADDING = 8;
    private static final int HEADER_HEIGHT = 26;
    private static final int FOOTER_HEIGHT = 32;
    private static final int LINE_HEIGHT = 11;
    private static final int INPUT_HEIGHT = 18;
    private static final int SUGGESTION_HEIGHT = 12;
    private static final int STATS_HEIGHT = 2 * LINE_HEIGHT + 8;
    private static final int PRIMARY_BUTTON_WIDTH = 112;
    private static final int SECONDARY_BUTTON_WIDTH = 64;
    private static final int BUTTON_GAP = 6;

    /** Just over the server's minimum gap between requests, so the last typed text is never dropped. */
    private static final int REQUEST_INTERVAL_TICKS = 5;
    private static final int SUGGESTION_BORDER = 0xFF000000;
    private static final int SUGGESTION_FILL = 0xFF202020;
    private static final int SUGGESTION_HIGHLIGHT_FILL = 0xFF3A3A5A;
    private static final int SUGGESTION_TEXT = 0xFFAAAAAA;
    private static final int SUGGESTION_HIGHLIGHT_TEXT = 0xFFFFFFFF;

    private EditBox nameBox;
    private String lastRequested = null;
    private String acceptedName = null;
    private int suggestionIndex = -1;
    private int ticksSinceRequest = REQUEST_INTERVAL_TICKS;

    private int panelLeft;
    private int panelTop;
    private int panelHeight;

    public ChronoRemoteRepositoryScreen() {
        super(Component.translatable("gui.temporalindustries.chrono_remote.repository.title"));
    }

    @Override
    protected void init() {
        panelHeight = HEADER_HEIGHT + PADDING + LINE_HEIGHT * 2 + INPUT_HEIGHT + PADDING
                + STATS_HEIGHT + PADDING + FOOTER_HEIGHT;
        panelLeft = (width - PANEL_WIDTH) / 2;
        panelTop = Math.max(0, (height - panelHeight) / 2);

        String previous = nameBox == null ? "" : nameBox.getValue();
        nameBox = new EditBox(font, panelLeft + PADDING, inputTop(), PANEL_WIDTH - 2 * PADDING, INPUT_HEIGHT,
                Component.translatable("gui.temporalindustries.chrono_remote.repository.name"));
        nameBox.setMaxLength(TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH);
        nameBox.setHint(Component.translatable("gui.temporalindustries.chrono_remote.repository.hint"));
        nameBox.setValue(previous);
        nameBox.setResponder(text -> {
            suggestionIndex = -1;
            if (!text.equals(acceptedName)) acceptedName = null;
        });
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        ChronosphereClientState.clearSuggestions();
        lastRequested = null;
        ticksSinceRequest = REQUEST_INTERVAL_TICKS;
    }

    @Override
    public void tick() {
        super.tick();
        ticksSinceRequest++;
        if (ticksSinceRequest >= REQUEST_INTERVAL_TICKS) requestSuggestions();
    }

    private int inputTop() {
        return panelTop + HEADER_HEIGHT + PADDING + LINE_HEIGHT * 2;
    }

    private int suggestionRowY(int row) {
        return inputTop() + INPUT_HEIGHT + row * SUGGESTION_HEIGHT;
    }

    private int statsTop() {
        return inputTop() + INPUT_HEIGHT + PADDING;
    }

    private int footerTop() {
        return panelTop + panelHeight - FOOTER_HEIGHT;
    }

    private int changeButtonLeft() {
        return panelLeft + PANEL_WIDTH - PADDING - PRIMARY_BUTTON_WIDTH;
    }

    private int closeButtonLeft() {
        return changeButtonLeft() - BUTTON_GAP - SECONDARY_BUTTON_WIDTH;
    }

    private int buttonTop() {
        return footerTop() + (FOOTER_HEIGHT - BUTTON_HEIGHT) / 2;
    }

    private void requestSuggestions() {
        String query = nameBox.getValue();
        if (query.equals(lastRequested)) return;
        lastRequested = query;
        ticksSinceRequest = 0;
        PacketDistributor.sendToServer(new ChronoRemoteRepositoryPacket(ChronoRemoteRepositoryPacket.Action.SUGGEST, query));
    }

    /** The matches for what is typed now; a late reply to older text is ignored. */
    private List<String> suggestions() {
        if (nameBox.getValue().equals(acceptedName)
                || !ChronosphereClientState.getSuggestionQuery().equals(nameBox.getValue())) return List.of();
        List<String> names = ChronosphereClientState.getSuggestions();
        if (names.size() == 1 && names.get(0).equalsIgnoreCase(nameBox.getValue().trim())) return List.of();
        return names;
    }

    private int suggestionRowAt(double mouseX, double mouseY) {
        int count = suggestions().size();
        for (int row = 0; row < count; row++) {
            if (isOver(mouseX, mouseY, nameBox.getX(), suggestionRowY(row), nameBox.getWidth(), SUGGESTION_HEIGHT)) return row;
        }
        return -1;
    }

    private void acceptSuggestion(int row) {
        List<String> names = suggestions();
        if (row < 0 || row >= names.size()) return;
        acceptedName = names.get(row);
        nameBox.setValue(acceptedName);
        nameBox.setFocused(true);
        lastRequested = null;
        ticksSinceRequest = REQUEST_INTERVAL_TICKS;
    }

    private boolean canChange() {
        String name = nameBox.getValue().trim();
        return !name.isEmpty() && !name.equalsIgnoreCase(ChronoRemoteClientState.getRepositoryName());
    }

    private void change(String name) {
        if (name.isBlank()) return;
        PacketDistributor.sendToServer(new ChronoRemoteRepositoryPacket(ChronoRemoteRepositoryPacket.Action.CHANGE, name));
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // The backdrop is drawn in render without the menu blur.
    }

    @Override
    public void removed() {
        ChronosphereClientState.clearSuggestions();
        ChronoRemoteClientState.updateRepositoryInfo("", null);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, BACKDROP_COLOR);
        drawBackground(guiGraphics, panelLeft, panelTop, PANEL_WIDTH, panelHeight);

        text(guiGraphics, font, title, panelLeft + PADDING + 2, panelTop + (HEADER_HEIGHT - font.lineHeight) / 2, TEXT_PRIMARY);

        int y = panelTop + HEADER_HEIGHT + PADDING;
        String current = ChronoRemoteClientState.getRepositoryName();
        text(guiGraphics, font, Component.translatable("gui.temporalindustries.chrono_remote.repository.current"),
                panelLeft + PADDING + 2, y, TEXT_MUTED);
        text(guiGraphics, font, fit(current.isEmpty()
                        ? Component.translatable("gui.temporalindustries.chrono_remote.repository.none") : Component.literal(current),
                PANEL_WIDTH - 2 * PADDING - 4), panelLeft + PADDING + 2, y + LINE_HEIGHT, TEXT_PRIMARY);

        renderStats(guiGraphics);
        guiGraphics.fill(panelLeft + 3, footerTop(), panelLeft + PANEL_WIDTH - 3, footerTop() + 1, DIVIDER_COLOR);
        drawButton(guiGraphics, font, closeButtonLeft(), buttonTop(), SECONDARY_BUTTON_WIDTH,
                Component.translatable("gui.temporalindustries.chrono_remote.close"), true, false, mouseX, mouseY);
        drawButton(guiGraphics, font, changeButtonLeft(), buttonTop(), PRIMARY_BUTTON_WIDTH,
                Component.translatable("gui.temporalindustries.chrono_remote.repository.change"), canChange(), true, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderSuggestions(guiGraphics, mouseX, mouseY);
    }

    /** The matches as a dropdown under the name box, drawn over everything below it. */
    private void renderSuggestions(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<String> names = suggestions();
        if (names.isEmpty()) return;

        int hoveredRow = suggestionRowAt(mouseX, mouseY);
        int x = nameBox.getX();
        int boxWidth = nameBox.getWidth();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 200);
        guiGraphics.fill(x - 1, suggestionRowY(0), x + boxWidth + 1, suggestionRowY(names.size()) + 1, SUGGESTION_BORDER);
        for (int row = 0; row < names.size(); row++) {
            int y = suggestionRowY(row);
            boolean highlighted = row == hoveredRow || row == suggestionIndex;
            guiGraphics.fill(x, y, x + boxWidth, y + SUGGESTION_HEIGHT, highlighted ? SUGGESTION_HIGHLIGHT_FILL : SUGGESTION_FILL);
            guiGraphics.drawString(font, fit(Component.literal(names.get(row)), boxWidth - 6), x + 3,
                    y + (SUGGESTION_HEIGHT - font.lineHeight) / 2 + 1,
                    highlighted ? SUGGESTION_HIGHLIGHT_TEXT : SUGGESTION_TEXT, false);
        }
        guiGraphics.pose().popPose();
    }

    /** What the typed repository holds, or that the name would create a new one. */
    private void renderStats(GuiGraphics guiGraphics) {
        int left = panelLeft + PADDING;
        int width = PANEL_WIDTH - 2 * PADDING;
        int top = statsTop();
        drawInset(guiGraphics, left, top, width, STATS_HEIGHT);

        int x = left + PADDING;
        int y = top + 5;
        String typed = nameBox.getValue();
        if (typed.isBlank()) {
            wrapped(guiGraphics, font, Component.translatable("gui.temporalindustries.chrono_remote.repository.type_hint"),
                    x, y, width - 2 * PADDING, TEXT_MUTED);
            return;
        }
        if (!ChronoRemoteClientState.getRepositoryInfoQuery().equals(typed)) return;

        TemporalWorldData.RepositoryStats stats = ChronoRemoteClientState.getRepositoryInfo();
        String prefix = "gui.temporalindustries.chrono_remote.repository.stats_";
        int textWidth = width - 2 * PADDING;
        if (stats == null) {
            wrapped(guiGraphics, font, unavailableMessage(), x, y, textWidth, TEXT_MUTED);
            return;
        }

        text(guiGraphics, font, fit(Component.translatable(prefix + "counts", stats.commits(), stats.branches(), stats.chunks()),
                textWidth), x, y, TEXT_PRIMARY);
        long now = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0L;
        Component last = stats.lastCommitTime() < 0L
                ? Component.translatable(prefix + "no_commits")
                : Component.translatable(prefix + "last", formatAge(now - stats.lastCommitTime()));
        Component detail = stats.currentBranch().isEmpty() ? last
                : Component.translatable(prefix + "branch", stats.currentBranch(), last);
        text(guiGraphics, font, fit(detail, textWidth), x, y + LINE_HEIGHT, TEXT_SECONDARY);
    }

    /** The text cut to maxWidth pixels, ending in an ellipsis if it had to be shortened. */
    private FormattedCharSequence fit(Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text.getVisualOrderText();
        FormattedText head = font.substrByWidth(text, maxWidth - font.width(CommonComponents.ELLIPSIS));
        return Language.getInstance().getVisualOrder(FormattedText.composite(head, CommonComponents.ELLIPSIS));
    }

    private static String formatAge(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        if (days > 0L) return days + "d " + hours + "h ago";
        if (hours > 0L) return hours + "h " + minutes + "m ago";
        if (minutes > 0L) return minutes + "m ago";
        return totalSeconds + "s ago";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int row = suggestionRowAt(mouseX, mouseY);
            if (row >= 0) {
                acceptSuggestion(row);
                return true;
            }
            if (canChange() && isOver(mouseX, mouseY, changeButtonLeft(), buttonTop(), PRIMARY_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                change(nameBox.getValue());
                return true;
            }
            if (isOver(mouseX, mouseY, closeButtonLeft(), buttonTop(), SECONDARY_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int count = suggestions().size();
        if (count > 0) {
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                suggestionIndex = (suggestionIndex + 1) % count;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                suggestionIndex = (suggestionIndex <= 0 ? count : suggestionIndex) - 1;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                acceptSuggestion(Math.max(suggestionIndex, 0));
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (suggestionIndex >= 0 && suggestionIndex < count) {
                acceptSuggestion(suggestionIndex);
                return true;
            }
            if (canChange()) {
                change(nameBox.getValue());
                return true;
            }
        }
        if (!nameBox.isFocused() && minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            ChronoRemoteWheelScreen.openSettings();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
