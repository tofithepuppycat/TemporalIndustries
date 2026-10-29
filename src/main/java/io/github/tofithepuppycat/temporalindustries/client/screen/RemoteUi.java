package io.github.tofithepuppycat.temporalindustries.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** The dark panel look shared by the Chrono Remote Control's screens. */
final class RemoteUi {
    static final int BUTTON_HEIGHT = 18;

    static final int BACKDROP_COLOR = 0x90000000;
    static final int PANEL_COLOR = 0xF2141420;
    static final int PANEL_BORDER_COLOR = 0xFF3A3A55;
    static final int SIDEBAR_COLOR = 0xFF1B1B2B;
    static final int DIVIDER_COLOR = 0xFF2C2C42;
    static final int TEXT_PRIMARY = 0xFFEDEDF5;
    static final int TEXT_SECONDARY = 0xFFA8A8BC;
    static final int TEXT_MUTED = 0xFF70708A;
    static final int ACCENT_COLOR = 0xFF8FD8FF;
    static final int PRIMARY_COLOR = 0xFF3F7F5A;
    static final int PRIMARY_HOVER_COLOR = 0xFF52A074;
    static final int SECONDARY_COLOR = 0xFF2C2C42;
    static final int SECONDARY_HOVER_COLOR = 0xFF3C3C5A;
    static final int DISABLED_COLOR = 0xFF26263A;

    private RemoteUi() {}

    static void drawButton(GuiGraphics guiGraphics, Font font, int x, int y, int width, Component label, boolean enabled,
                           boolean primary, int mouseX, int mouseY) {
        boolean hovered = enabled && isOver(mouseX, mouseY, x, y, width, BUTTON_HEIGHT);
        int color = !enabled ? DISABLED_COLOR
                : primary ? (hovered ? PRIMARY_HOVER_COLOR : PRIMARY_COLOR)
                : (hovered ? SECONDARY_HOVER_COLOR : SECONDARY_COLOR);
        drawPanel(guiGraphics, x, y, width, BUTTON_HEIGHT, color, color);
        guiGraphics.drawCenteredString(font, label, x + width / 2, y + (BUTTON_HEIGHT - font.lineHeight) / 2 + 1,
                enabled ? TEXT_PRIMARY : TEXT_MUTED);
    }

    /** A filled rectangle with notched corners and a 1px border. */
    static void drawPanel(GuiGraphics guiGraphics, int x, int y, int w, int h, int fill, int border) {
        guiGraphics.fill(x + 1, y, x + w - 1, y + h, border);
        guiGraphics.fill(x, y + 1, x + w, y + h - 1, border);
        guiGraphics.fill(x + 2, y + 1, x + w - 2, y + h - 1, fill);
        guiGraphics.fill(x + 1, y + 2, x + w - 1, y + h - 2, fill);
    }

    static boolean isOver(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
