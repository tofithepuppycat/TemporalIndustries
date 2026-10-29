package io.github.tofithepuppycat.temporalindustries.client.window;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the light container panel of vanilla GUIs at any size: a one pixel black outline with
 * notched corners, a two pixel white highlight along the top and left, a two pixel dark shadow
 * along the bottom and right, and a gray body.
 */
public final class VanillaBackground {
    private static final int OUTLINE = 0xFF000000;
    private static final int HIGHLIGHT = 0xFFFFFFFF;
    private static final int BODY = 0xFFC6C6C6;
    private static final int SHADOW = 0xFF555555;
    /** Where the highlight and the shadow meet at the top-right and bottom-left corners. */
    private static final int CORNER_BLEND = 0xFF8B8B8B;

    private VanillaBackground() {}

    public static void render(GuiGraphics g, int x, int y, int width, int height) {
        int w = width;
        int h = height;

        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, BODY);

        g.fill(x + 2, y + 1, x + w - 3, y + 2, HIGHLIGHT);
        g.fill(x + 1, y + 2, x + w - 3, y + 3, HIGHLIGHT);
        g.fill(x + 1, y + 2, x + 3, y + h - 3, HIGHLIGHT);
        pixel(g, x + 3, y + 3, HIGHLIGHT);

        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 3, SHADOW);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 2, SHADOW);
        g.fill(x + 3, y + h - 2, x + w - 2, y + h - 1, SHADOW);
        pixel(g, x + w - 4, y + h - 4, SHADOW);

        pixel(g, x + w - 3, y + 2, CORNER_BLEND);
        pixel(g, x + 2, y + h - 3, CORNER_BLEND);

        g.fill(x + 2, y, x + w - 3, y + 1, OUTLINE);
        g.fill(x, y + 2, x + 1, y + h - 3, OUTLINE);
        g.fill(x + w - 1, y + 3, x + w, y + h - 2, OUTLINE);
        g.fill(x + 3, y + h - 1, x + w - 2, y + h, OUTLINE);
        pixel(g, x + 1, y + 1, OUTLINE);
        pixel(g, x + w - 3, y + 1, OUTLINE);
        pixel(g, x + w - 2, y + 2, OUTLINE);
        pixel(g, x + 1, y + h - 3, OUTLINE);
        pixel(g, x + 2, y + h - 2, OUTLINE);
        pixel(g, x + w - 2, y + h - 2, OUTLINE);
    }

    private static void pixel(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 1, color);
    }
}
