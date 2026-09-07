package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Shared layered rendering for the small square tabs hanging off a panel's right edge: a
 * menu_base_right.png backing, menu_icon_base.png centered on top, then the tab's icon. */
public final class IconTabRenderer {
    /** Backing for the tabs; the "right" variant faces its rounded corner outward. */
    private static final ResourceLocation MENU_BASE_RIGHT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/menu_base_right.png");
    private static final ResourceLocation MENU_ICON_BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/menu_icon_base.png");

    /** Native resolution of menu_base_right.png; drawn 1:1 to avoid scaling artifacts on the rounded corners. */
    public static final int SIZE = 32;
    private static final int ICON_BASE_SIZE = 22;
    /** menu_icon_base.png's art sits left of center, so nudge it (and icons on top of it) right to center on the tab. */
    public static final int ICON_X_NUDGE = 2;

    private IconTabRenderer() {}

    /** Draws the menu_base_right -> menu_icon_base layers, plus an optional tint for selected/hovered state. */
    public static void renderBackground(GuiGraphics guiGraphics, int x, int y, int tint) {
        blitNative(guiGraphics, MENU_BASE_RIGHT_TEXTURE, x, y, SIZE);

        int iconBaseOffset = (SIZE - ICON_BASE_SIZE) / 2;
        blitNative(guiGraphics, MENU_ICON_BASE_TEXTURE, x + iconBaseOffset + ICON_X_NUDGE, y + iconBaseOffset, ICON_BASE_SIZE);

        if (tint != 0) {
            guiGraphics.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, tint);
        }
    }

    /** Draws icon (at its own native resolution) centered within the SIZE x SIZE tab at (x, y). */
    public static void renderIcon(GuiGraphics guiGraphics, ResourceLocation icon, int iconSize, int x, int y) {
        int offset = (SIZE - iconSize) / 2;
        blitNative(guiGraphics, icon, x + offset + ICON_X_NUDGE, y + offset, iconSize);
    }

    /** Blits at native resolution; the 6-arg blit overload assumes a 256x256 atlas and would
     * undersample these small sprites. */
    private static void blitNative(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int size) {
        guiGraphics.blit(texture, x, y, size, size, 0.0F, 0.0F, size, size, size, size);
    }
}
