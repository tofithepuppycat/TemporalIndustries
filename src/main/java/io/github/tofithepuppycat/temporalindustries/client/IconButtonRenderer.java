package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Shared rendering for the small inline icon buttons (Show Changes, Jump, ...) that replace
 * vanilla Minecraft {@code Button}s in the mod's textured GUIs: menu_icon_base_small.png as the
 * backing square, with the button's own icon drawn on top at the same native 16x16 size — the
 * same layered technique as {@link IconTabRenderer}, just for inline buttons rather than the
 * panel-edge tabs. */
public final class IconButtonRenderer {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/menu_icon_base_small.png");

    /** Native resolution of both menu_icon_base_small.png and every icon meant to sit on top of it. */
    public static final int SIZE = 16;

    private IconButtonRenderer() {}

    /** Draws the menu_icon_base_small.png backing, plus an optional tint (e.g. for active/hovered
     * state) washed over it. Callers draw their own icon on top after. */
    public static void renderBackground(GuiGraphics guiGraphics, int x, int y, int tint) {
        blitNative(guiGraphics, BASE_TEXTURE, x, y);
        if (tint != 0) {
            guiGraphics.fill(x, y, x + SIZE, y + SIZE, tint);
        }
    }

    /** Draws icon (at its own native SIZE x SIZE resolution) at (x, y). */
    public static void renderIcon(GuiGraphics guiGraphics, ResourceLocation icon, int x, int y) {
        blitNative(guiGraphics, icon, x, y);
    }

    /** Blits a square texture 1:1 at its own native resolution — the 6-arg blit overload assumes a
     * 256x256 atlas when normalizing UVs, which would sample only a sliver of these small sprites. */
    private static void blitNative(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y) {
        guiGraphics.blit(texture, x, y, SIZE, SIZE, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
    }
}
