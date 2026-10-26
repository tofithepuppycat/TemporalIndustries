package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared rendering for the small inline icon buttons that replace vanilla {@code Button}s in the
 * mod's textured GUIs: a menu_icon_base_small.png backing with the button's icon on top.
 */
public final class IconButtonRenderer {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/menu_icon_base_small.png");

    /** Native resolution of the backing and icon textures. */
    public static final int SIZE = 16;

    private static final int TOGGLE_ON_TINT = 0x6055FF55;
    private static final int TOGGLE_OFF_TINT = 0x60FF5555;

    private IconButtonRenderer() {}

    /** Draws the backing, plus an optional tint for active/hovered state. */
    public static void renderBackground(GuiGraphics guiGraphics, int x, int y, int tint) {
        blitNative(guiGraphics, BASE_TEXTURE, x, y);
        if (tint != 0) {
            guiGraphics.fill(x, y, x + SIZE, y + SIZE, tint);
        }
    }

    /** Draws a boolean toggle: backing washed green when enabled or red when disabled, darkened on hover, with the icon on top. */
    public static void renderToggle(GuiGraphics guiGraphics, ResourceLocation icon, boolean enabled, boolean hovered,
                                    int x, int y) {
        renderBackground(guiGraphics, x, y, enabled ? TOGGLE_ON_TINT : TOGGLE_OFF_TINT);
        if (hovered) {
            guiGraphics.fill(x, y, x + SIZE, y + SIZE, 0x40000000);
        }
        renderIcon(guiGraphics, icon, x, y);
    }

    /** Draws icon (at its own native SIZE x SIZE resolution) at (x, y). */
    public static void renderIcon(GuiGraphics guiGraphics, ResourceLocation icon, int x, int y) {
        blitNative(guiGraphics, icon, x, y);
    }

    /**
     * Blits at native resolution; the 6-arg blit overload assumes a 256x256 atlas and would
     * undersample these small sprites.
     */
    private static void blitNative(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y) {
        guiGraphics.blit(texture, x, y, SIZE, SIZE, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
    }
}
