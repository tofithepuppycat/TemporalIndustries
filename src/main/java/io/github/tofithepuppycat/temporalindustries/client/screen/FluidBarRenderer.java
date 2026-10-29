package io.github.tofithepuppycat.temporalindustries.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Tiles a fluid's still texture, tinted with an entropy color, over a filled portion of a bar;
 * shared by every Order/Chaos tank bar in the mod. The fluid is drawn one pixel inside the given
 * bounds, which are the bar's full size including its border.
 */
final class FluidBarRenderer {
    private FluidBarRenderer() {}

    private static final int BORDER = 1;

    /** Fills bottom-up over a vertical bar. */
    static void renderVertical(GuiGraphics guiGraphics, int barX, int barY, int barWidth, int barHeight,
                                int amount, int capacity, FluidType fluidType, int tintColor) {
        int x = barX + BORDER;
        int y = barY + BORDER;
        int width = barWidth - 2 * BORDER;
        int height = barHeight - 2 * BORDER;
        if (capacity <= 0 || amount <= 0) return;
        int filled = Math.max(1, Math.round((amount / (float) capacity) * height));
        filled = Math.min(height, filled);

        int bottom = y + height;
        int top = bottom - filled;

        TextureAtlasSprite sprite = stillSprite(fluidType);
        float[] rgb = unpackColor(tintColor);

        guiGraphics.enableScissor(x, top, x + width, bottom);
        for (int drawY = bottom - 16; drawY > top - 16; drawY -= 16) {
            guiGraphics.blit(x, drawY, 0, width, 16, sprite, rgb[0], rgb[1], rgb[2], 1f);
        }
        guiGraphics.disableScissor();
    }

    private static TextureAtlasSprite stillSprite(FluidType fluidType) {
        ResourceLocation stillTexture = IClientFluidTypeExtensions.of(fluidType).getStillTexture();
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(stillTexture);
    }

    private static float[] unpackColor(int color) {
        return new float[] {
                ((color >> 16) & 0xFF) / 255f,
                ((color >> 8) & 0xFF) / 255f,
                (color & 0xFF) / 255f
        };
    }
}
