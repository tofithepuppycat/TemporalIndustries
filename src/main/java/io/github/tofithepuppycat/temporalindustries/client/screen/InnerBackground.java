package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Dark layer drawn behind a panel texture so its transparent cut-outs (tank wells, screens) show it;
 * inset from the panel edge to stay clear of the rounded corners.
 */
final class InnerBackground {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/inner.png");
    private static final int TEXTURE_SIZE = 256;
    private static final int INSET = 3;

    private InnerBackground() {}

    static void render(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        guiGraphics.blit(TEXTURE, x + INSET, y + INSET, 0, 0, width - 2 * INSET, height - 2 * INSET, TEXTURE_SIZE, TEXTURE_SIZE);
    }
}
