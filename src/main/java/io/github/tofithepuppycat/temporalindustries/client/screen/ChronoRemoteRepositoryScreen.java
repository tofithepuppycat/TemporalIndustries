package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteRepositoryPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
    private static final int SUGGESTION_ROWS = 5;
    private static final int SUGGESTION_HEIGHT = 14;
    private static final int PRIMARY_BUTTON_WIDTH = 112;
    private static final int SECONDARY_BUTTON_WIDTH = 64;
    private static final int BUTTON_GAP = 6;

    private static final int SUGGESTION_HOVER_COLOR = 0xFF2C2C42;

    private EditBox nameBox;
    private String lastRequested = null;

    private int panelLeft;
    private int panelTop;
    private int panelHeight;

    public ChronoRemoteRepositoryScreen() {
        super(Component.translatable("gui.temporalindustries.chrono_remote.repository.title"));
    }

    @Override
    protected void init() {
        panelHeight = HEADER_HEIGHT + PADDING + LINE_HEIGHT * 2 + INPUT_HEIGHT + PADDING
                + SUGGESTION_ROWS * SUGGESTION_HEIGHT + PADDING + FOOTER_HEIGHT;
        panelLeft = (width - PANEL_WIDTH) / 2;
        panelTop = Math.max(0, (height - panelHeight) / 2);

        String previous = nameBox == null ? "" : nameBox.getValue();
        nameBox = new EditBox(font, panelLeft + PADDING, inputTop(), PANEL_WIDTH - 2 * PADDING, INPUT_HEIGHT,
                Component.translatable("gui.temporalindustries.chrono_remote.repository.name"));
        nameBox.setMaxLength(TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH);
        nameBox.setHint(Component.translatable("gui.temporalindustries.chrono_remote.repository.hint"));
        nameBox.setValue(previous);
        nameBox.setResponder(text -> requestSuggestions());
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        ChronosphereClientState.clearSuggestions();
        requestSuggestions();
    }

    private int inputTop() {
        return panelTop + HEADER_HEIGHT + PADDING + LINE_HEIGHT * 2;
    }

    private int suggestionsTop() {
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
        PacketDistributor.sendToServer(new ChronoRemoteRepositoryPacket(ChronoRemoteRepositoryPacket.Action.SUGGEST, query));
    }

    /** The matches for what is typed now; a late reply to older text is ignored. */
    private List<String> suggestions() {
        return ChronosphereClientState.getSuggestionQuery().equals(nameBox.getValue())
                ? ChronosphereClientState.getSuggestions() : List.of();
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
    public void removed() {
        ChronosphereClientState.clearSuggestions();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, BACKDROP_COLOR);
        drawPanel(guiGraphics, panelLeft, panelTop, PANEL_WIDTH, panelHeight, PANEL_COLOR, PANEL_BORDER_COLOR);

        guiGraphics.drawString(font, title, panelLeft + PADDING + 2, panelTop + (HEADER_HEIGHT - font.lineHeight) / 2, ACCENT_COLOR);

        int y = panelTop + HEADER_HEIGHT + PADDING;
        String current = ChronoRemoteClientState.getRepositoryName();
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.chrono_remote.repository.current"),
                panelLeft + PADDING + 2, y, TEXT_MUTED);
        guiGraphics.drawString(font, current.isEmpty()
                        ? Component.translatable("gui.temporalindustries.chrono_remote.repository.none") : Component.literal(current),
                panelLeft + PADDING + 2, y + LINE_HEIGHT, TEXT_PRIMARY);

        renderSuggestions(guiGraphics, mouseX, mouseY);
        guiGraphics.fill(panelLeft + 1, footerTop(), panelLeft + PANEL_WIDTH - 1, footerTop() + 1, DIVIDER_COLOR);
        drawButton(guiGraphics, font, closeButtonLeft(), buttonTop(), SECONDARY_BUTTON_WIDTH,
                Component.translatable("gui.temporalindustries.chrono_remote.close"), true, false, mouseX, mouseY);
        drawButton(guiGraphics, font, changeButtonLeft(), buttonTop(), PRIMARY_BUTTON_WIDTH,
                Component.translatable("gui.temporalindustries.chrono_remote.repository.change"), canChange(), true, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderSuggestions(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int top = suggestionsTop();
        int left = panelLeft + PADDING;
        int width = PANEL_WIDTH - 2 * PADDING;
        drawPanel(guiGraphics, left, top, width, SUGGESTION_ROWS * SUGGESTION_HEIGHT, SIDEBAR_COLOR, DIVIDER_COLOR);

        List<String> names = suggestions();
        if (names.isEmpty()) {
            guiGraphics.drawString(font, Component.translatable(nameBox.getValue().isBlank()
                            ? "gui.temporalindustries.chrono_remote.repository.type_hint"
                            : "gui.temporalindustries.chrono_remote.repository.no_matches"),
                    left + PADDING, top + (SUGGESTION_HEIGHT - font.lineHeight) / 2 + 1, TEXT_MUTED);
            return;
        }
        for (int i = 0; i < Math.min(names.size(), SUGGESTION_ROWS); i++) {
            int rowTop = top + i * SUGGESTION_HEIGHT;
            if (isOver(mouseX, mouseY, left + 1, rowTop, width - 2, SUGGESTION_HEIGHT)) {
                guiGraphics.fill(left + 1, rowTop, left + width - 1, rowTop + SUGGESTION_HEIGHT, SUGGESTION_HOVER_COLOR);
            }
            guiGraphics.drawString(font, names.get(i), left + PADDING, rowTop + (SUGGESTION_HEIGHT - font.lineHeight) / 2 + 1, TEXT_PRIMARY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (canChange() && isOver(mouseX, mouseY, changeButtonLeft(), buttonTop(), PRIMARY_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                change(nameBox.getValue());
                return true;
            }
            if (isOver(mouseX, mouseY, closeButtonLeft(), buttonTop(), SECONDARY_BUTTON_WIDTH, BUTTON_HEIGHT)) {
                onClose();
                return true;
            }

            List<String> names = suggestions();
            int left = panelLeft + PADDING;
            for (int i = 0; i < Math.min(names.size(), SUGGESTION_ROWS); i++) {
                if (isOver(mouseX, mouseY, left, suggestionsTop() + i * SUGGESTION_HEIGHT, PANEL_WIDTH - 2 * PADDING, SUGGESTION_HEIGHT)) {
                    nameBox.setValue(names.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && canChange()) {
            change(nameBox.getValue());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
