package io.github.tofithepuppycat.temporalindustries.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;

/** Tiles a fluid's still texture (from the block atlas), tinted with an entropy color, over a
 * filled portion of a bar — the rendering technique shared by every Order/Chaos tank bar in the
 * mod. {@link EntropyCondenserScreen} fills its (tall, vertical) bars bottom-up; Chronovault and
 * Chronosphere's bars are short and wide, so they fill left-to-right instead. */
final class FluidBarRenderer {
    private FluidBarRenderer() {}

    /** Fills bottom-up over a vertical bar. */
    static void renderVertical(GuiGraphics guiGraphics, int x, int y, int width, int height,
                                int amount, int capacity, FluidType fluidType, int tintColor) {
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

    /** Fills left-to-right over a horizontal bar. */
    static void renderHorizontal(GuiGraphics guiGraphics, int x, int y, int width, int height,
                                  int amount, int capacity, FluidType fluidType, int tintColor) {
        if (capacity <= 0 || amount <= 0) return;
        int filled = Math.max(1, Math.round((amount / (float) capacity) * width));
        filled = Math.min(width, filled);

        TextureAtlasSprite sprite = stillSprite(fluidType);
        float[] rgb = unpackColor(tintColor);

        guiGraphics.enableScissor(x, y, x + filled, y + height);
        for (int drawX = x; drawX < x + filled; drawX += 16) {
            guiGraphics.blit(drawX, y, 0, 16, height, sprite, rgb[0], rgb[1], rgb[2], 1f);
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
