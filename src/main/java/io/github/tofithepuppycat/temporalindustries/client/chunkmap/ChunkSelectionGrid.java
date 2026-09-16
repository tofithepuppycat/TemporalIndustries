package io.github.tofithepuppycat.temporalindustries.client.chunkmap;

import io.github.tofithepuppycat.temporalindustries.chronomap.ChronoMapSampler;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChunkArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/** Renders and hit-tests a grid of chunk cells centred on an anchor chunk, shared by the
 * Chronosphere's claim overlay and the Portable Chrono Marker's area-select screen. Purely
 * layout/rendering/hit-testing; callers own what each cell means via {@link CellPainter}. */
public final class ChunkSelectionGrid {
    public static final int DEFAULT_CELL_SIZE = 32;
    public static final int CELL_GAP = 0;

    private final int radius;
    private final ChunkArea.Shape shape;
    private final int cellSize;
    private final int gridSize;
    private final int gridPixels;

    /** A circular grid at the default cell size. */
    public ChunkSelectionGrid(int radius) {
        this(radius, ChunkArea.Shape.CIRCLE, DEFAULT_CELL_SIZE);
    }

    /** {@code cellSize} lets a wide-radius grid shrink its cells to stay inside its host panel. */
    public ChunkSelectionGrid(int radius, ChunkArea.Shape shape, int cellSize) {
        this.radius = radius;
        this.shape = shape;
        this.cellSize = cellSize;
        this.gridSize = radius * 2 + 1;
        this.gridPixels = gridSize * cellSize + (gridSize - 1) * CELL_GAP;
    }

    public int radius() {
        return radius;
    }

    public int gridPixels() {
        return gridPixels;
    }

    /** Whether offset (dx, dz) from the anchor chunk falls within the selectable area. */
    public boolean isWithinRadius(int dx, int dz) {
        return shape.contains(radius, dx, dz);
    }

    /** Draws every in-radius cell tinted/textured per {@code painter}: a flat status color, or a
     * terrain thumbnail tinted translucently by status once available. */
    public void render(GuiGraphics guiGraphics, int gridX, int gridY, ChunkPos anchor, CellPainter painter) {
        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                int dx = col - radius;
                int dz = row - radius;
                if (!isWithinRadius(dx, dz)) continue;

                int cellX = gridX + col * cellSize;
                int cellY = gridY + row * cellSize;
                long key = new ChunkPos(anchor.x + dx, anchor.z + dz).toLong();

                int tint = painter.tint(dx, dz, key);
                ResourceLocation terrain = painter.texture(key);
                if (terrain != null) {
                    int size = ChronoMapSampler.SIZE;
                    guiGraphics.blit(terrain, cellX, cellY, cellSize, cellSize, 0.0F, 0.0F, size, size, size, size);
                    // Status tint over the terrain, translucent so the sampled ground stays visible.
                    guiGraphics.fill(cellX, cellY, cellX + cellSize, cellY + cellSize, (0x80 << 24) | (tint & 0xFFFFFF));
                } else {
                    guiGraphics.fill(cellX, cellY, cellX + cellSize, cellY + cellSize, tint);
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
        int col = (int) ((mouseX - gridX) / cellSize);
        int row = (int) ((mouseY - gridY) / cellSize);
        if (col < 0 || col >= gridSize || row < 0 || row >= gridSize) return null;

        int dx = col - radius;
        int dz = row - radius;
        if (!isWithinRadius(dx, dz)) return null; // outside the shape: not drawn, not clickable

        return new ChunkPos(anchor.x + dx, anchor.z + dz);
    }

    /** Like {@link #cellAt}, but clamps to the grid's bounds instead of returning null when the
     * cursor strays outside it (e.g. mid drag) or lands off-shape. Returned as a {dx, dz} offset
     * from the anchor rather than a {@link ChunkPos} since off-shape offsets have no valid chunk. */
    public int[] clampedOffsetAt(double mouseX, double mouseY, int gridX, int gridY) {
        double clampedX = Math.min(Math.max(mouseX, gridX), gridX + gridPixels - 1);
        double clampedY = Math.min(Math.max(mouseY, gridY), gridY + gridPixels - 1);
        int col = (int) ((clampedX - gridX) / cellSize);
        int row = (int) ((clampedY - gridY) / cellSize);
        col = Math.min(Math.max(col, 0), gridSize - 1);
        row = Math.min(Math.max(row, 0), gridSize - 1);
        return new int[] { col - radius, row - radius };
    }

    /** Fills the rectangle of cells spanning (dx1, dz1) to (dx2, dz2), inclusive, in either order. */
    public void renderRectHighlight(GuiGraphics guiGraphics, int gridX, int gridY, int dx1, int dz1, int dx2, int dz2, int color) {
        int minCol = Math.min(dx1, dx2) + radius;
        int maxCol = Math.max(dx1, dx2) + radius;
        int minRow = Math.min(dz1, dz2) + radius;
        int maxRow = Math.max(dz1, dz2) + radius;
        int x1 = gridX + minCol * cellSize;
        int y1 = gridY + minRow * cellSize;
        int x2 = gridX + (maxCol + 1) * cellSize;
        int y2 = gridY + (maxRow + 1) * cellSize;
        guiGraphics.fill(x1, y1, x2, y2, color);
    }

    public interface CellPainter {
        int tint(int dx, int dz, long chunkKey);

        @Nullable
        ResourceLocation texture(long chunkKey);
    }
}
