package io.github.tofithepuppycat.temporalindustries.client.screen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Order/Chaos balance gauge shared by the machine menus: fills from the center tick outward,
 * colored by the gradients in {@code temporalindustries.aseprite} (purple for Order, cyan for
 * Chaos).
 */
final class EntropyBalanceBar {
    private static final int[] ORDER_GRADIENT = {
            0x6f4fab, 0x8d6acc, 0xb38ef3, 0xcfa0f3, 0xfecbe6};
    private static final int[] CHAOS_GRADIENT = {
            0x052a32, 0x034150, 0x0a5060, 0x009295, 0x29dfeb};

    private EntropyBalanceBar() {}

    /** @param balance -1 (full order) .. +1 (full chaos) */
    static void render(GuiGraphics guiGraphics, int x, int y, int width, int height, float balance) {
        guiGraphics.fill(x, y, x + width, y + height, 0xFF000000);

        int mid = x + width / 2;
        int half = width / 2 - 1;
        int filled = Math.round(Math.min(1f, Math.abs(balance)) * half);
        int[] gradient = balance >= 0 ? CHAOS_GRADIENT : ORDER_GRADIENT;
        for (int i = 0; i < filled; i++) {
            int color = 0xFF000000 | gradient[i * gradient.length / half];
            int px = balance >= 0 ? mid + i : mid - 1 - i;
            guiGraphics.fill(px, y + 1, px + 1, y + height - 1, color);
        }
        guiGraphics.fill(mid, y, mid + 1, y + height, 0xFF888888);
    }
}
