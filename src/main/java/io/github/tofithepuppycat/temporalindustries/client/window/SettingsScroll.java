package io.github.tofithepuppycat.temporalindustries.client.window;

import net.minecraft.client.gui.GuiGraphics;

/** Vertical scroll state and scrollbar for a settings overlay taller than its viewport. */
final class SettingsScroll {
    private static final int WHEEL_STEP = 12;
    private static final int BAR_WIDTH = 3;
    private static final int MIN_THUMB_HEIGHT = 12;
    private static final int COLOR_TRACK = 0x30000000;
    private static final int COLOR_THUMB = 0xA0505050;

    private int offset;

    int offset() {
        return offset;
    }

    void reset() {
        offset = 0;
    }

    void scroll(double wheel, int contentHeight, int viewportHeight) {
        offset = clamp(offset - (int) Math.round(wheel * WHEEL_STEP), contentHeight, viewportHeight);
    }

    /** Re-clamps the offset to the current sizes; call before laying out scrolled content. */
    void update(int contentHeight, int viewportHeight) {
        offset = clamp(offset, contentHeight, viewportHeight);
    }

    void renderBar(GuiGraphics guiGraphics, int x, int top, int contentHeight, int viewportHeight) {
        int maxScroll = contentHeight - viewportHeight;
        if (maxScroll <= 0) return;
        guiGraphics.fill(x, top, x + BAR_WIDTH, top + viewportHeight, COLOR_TRACK);
        int thumbHeight = Math.max(MIN_THUMB_HEIGHT, viewportHeight * viewportHeight / contentHeight);
        int thumbY = top + (viewportHeight - thumbHeight) * offset / maxScroll;
        guiGraphics.fill(x, thumbY, x + BAR_WIDTH, thumbY + thumbHeight, COLOR_THUMB);
    }

    private static int clamp(int value, int contentHeight, int viewportHeight) {
        return Math.max(0, Math.min(Math.max(0, contentHeight - viewportHeight), value));
    }
}
