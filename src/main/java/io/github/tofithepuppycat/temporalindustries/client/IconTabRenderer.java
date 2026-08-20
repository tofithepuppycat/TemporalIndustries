package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Shared layered rendering for the small square tabs the Chronosphere/Chronovault GUIs hang off
 * their panel's right edge (settings, auto-track, ...): a menu_base_right.png backing,
 * menu_icon_base.png centered on top of that as the icon's own backdrop, then the tab's specific
 * icon drawn on top of that. */
public final class IconTabRenderer {
    /** Backing for the tabs, which all hang off the panel's right edge (bookmark, auto-track,
     * settings) — the "right" variant faces its rounded corner outward on that side. */
    private static final ResourceLocation MENU_BASE_RIGHT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/menu_base_right.png");
    private static final ResourceLocation MENU_ICON_BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/menu_icon_base.png");

    /** Native resolution of menu_base_right.png — tabs are drawn 1:1 at this size so the sprite's
     * rounded corners don't pick up scaling artifacts. */
    public static final int SIZE = 32;
    private static final int ICON_BASE_SIZE = 22;
    /** menu_icon_base.png's own art (and, since it's meant to sit centered inside that backdrop,
     * every icon — including the bookmark/auto-track placeholder glyphs — drawn on top of it) sits
     * left of center within its square, so nudge all of it right to actually land centered on the
     * tab. Public so callers positioning their own placeholder glyphs can apply the same nudge. */
    public static final int ICON_X_NUDGE = 2;

    private IconTabRenderer() {}

    /** Draws the menu_base_right -> menu_icon_base layers, plus an optional tint (e.g. for
     * selected/hovered state) washed over the whole tab. Callers draw their own icon on top after. */
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

    /** Blits a square texture 1:1 at its own native resolution — the 6-arg blit overload assumes a
     * 256x256 atlas when normalizing UVs, which would sample only a sliver of these small sprites. */
    private static void blitNative(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int size) {
        guiGraphics.blit(texture, x, y, size, size, 0.0F, 0.0F, size, size, size, size);
    }
}
