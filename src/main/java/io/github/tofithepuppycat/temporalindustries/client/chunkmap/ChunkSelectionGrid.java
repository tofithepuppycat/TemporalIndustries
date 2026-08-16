package io.github.tofithepuppycat.temporalindustries.client.chunkmap;

import io.github.tofithepuppycat.temporalindustries.chronomap.ChronoMapSampler;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChunkArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Renders and hit-tests a circular grid of chunk cells centred on an anchor chunk — the chunk
 * selection map originally built for the Chronosphere's claim overlay ({@link
 * io.github.tofithepuppycat.temporalindustries.client.screen.ChronosphereScreen}), extracted so the
 * Portable Chrono Marker's own area-select screen can present the exact same grid, colors-aside.
 * Purely layout/rendering/hit-testing; callers own what each cell actually means (claimed, blocked,
 * selected, terrain thumbnail, ...) via {@link CellPainter}.
 */
public final class ChunkSelectionGrid {
    public static final int CELL_SIZE = 32;
    public static final int CELL_GAP = 3;
    private static final int COLOR_BORDER = 0xFF000000;

    private final int radius;
    private final int gridSize;
    private final int gridPixels;

    public ChunkSelectionGrid(int radius) {
        this.radius = radius;
        this.gridSize = radius * 2 + 1;
        this.gridPixels = gridSize * CELL_SIZE + (gridSize - 1) * CELL_GAP;
    }

    public int radius() {
        return radius;
    }

    public int gridPixels() {
        return gridPixels;
    }

    /** Whether offset (dx, dz) from the anchor chunk falls within the circular selectable area. */
    public boolean isWithinRadius(int dx, int dz) {
        return ChunkArea.isWithinRadius(radius, dx, dz);
    }

    /** Draws every in-radius cell as a bordered square, tinted/textured per {@code painter} —
     * either a flat status color, or (once a terrain thumbnail is available) that terrain tinted
     * translucently by status, matching the Chronosphere screen's original map overlay look. */
    public void render(GuiGraphics guiGraphics, int gridX, int gridY, ChunkPos anchor, CellPainter painter) {
        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                int dx = col - radius;
                int dz = row - radius;
                if (!isWithinRadius(dx, dz)) continue;

                int cellX = gridX + col * (CELL_SIZE + CELL_GAP);
                int cellY = gridY + row * (CELL_SIZE + CELL_GAP);
                long key = new ChunkPos(anchor.x + dx, anchor.z + dz).toLong();

                guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, COLOR_BORDER);

                int innerX0 = cellX + 1, innerY0 = cellY + 1, innerX1 = cellX + CELL_SIZE - 1, innerY1 = cellY + CELL_SIZE - 1;
                int tint = painter.tint(dx, dz, key);
                ResourceLocation terrain = painter.texture(key);
                if (terrain != null) {
                    int inner = CELL_SIZE - 2;
                    int size = ChronoMapSampler.SIZE;
                    guiGraphics.blit(terrain, innerX0, innerY0, inner, inner, 0.0F, 0.0F, size, size, size, size);
                    // Status tint over the terrain, translucent so the sampled ground stays visible.
                    guiGraphics.fill(innerX0, innerY0, innerX1, innerY1, (0x80 << 24) | (tint & 0xFFFFFF));
                } else {
                    guiGraphics.fill(innerX0, innerY0, innerX1, innerY1, tint);
                }
            }
        }
    }

    /** The chunk under (mouseX, mouseY), or null if the cursor isn't over any in-radius cell. */
    @Nullable
    public ChunkPos cellAt(double mouseX, double mouseY, int gridX, int gridY, ChunkPos anchor) {
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + gridPixels || mouseY >= gridY + gridPixels) {
            return null;
        }
        int col = (int) ((mouseX - gridX) / (CELL_SIZE + CELL_GAP));
        int row = (int) ((mouseY - gridY) / (CELL_SIZE + CELL_GAP));
        double cellLocalX = (mouseX - gridX) - col * (CELL_SIZE + CELL_GAP);
        double cellLocalY = (mouseY - gridY) - row * (CELL_SIZE + CELL_GAP);
        if (cellLocalX > CELL_SIZE || cellLocalY > CELL_SIZE) return null; // clicked in the gap
        if (col < 0 || col >= gridSize || row < 0 || row >= gridSize) return null;

        int dx = col - radius;
        int dz = row - radius;
        if (!isWithinRadius(dx, dz)) return null; // outside the circle: not drawn, not clickable

        return new ChunkPos(anchor.x + dx, anchor.z + dz);
    }

    public interface CellPainter {
        int tint(int dx, int dz, long chunkKey);

        @Nullable
        ResourceLocation texture(long chunkKey);
    }
}
