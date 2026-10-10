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

    /** {@link #gridPixels()} at the given zoom factor, for callers rendering a scaled-up grid
     * (e.g. zoomed into a viewport smaller than the full grid). */
    public int gridPixels(float zoom) {
        int cs = effectiveCellSize(zoom);
        return gridSize * cs + (gridSize - 1) * CELL_GAP;
    }

    private int effectiveCellSize(float zoom) {
        return Math.max(1, Math.round(cellSize * zoom));
    }

    /** Whether offset (dx, dz) from the anchor chunk falls within the selectable area. */
    public boolean isWithinRadius(int dx, int dz) {
        return shape.contains(radius, dx, dz);
    }

    /** Draws every in-radius cell tinted/textured per {@code painter}: a flat status color, or a
     * terrain thumbnail tinted translucently by status once available. */
    public void render(GuiGraphics guiGraphics, int gridX, int gridY, ChunkPos anchor, CellPainter painter) {
        render(guiGraphics, gridX, gridY, 1.0F, anchor, painter);
    }

    /** Zoomed variant of {@link #render}: {@code gridX}/{@code gridY} are the (possibly panned)
     * top-left of the scaled grid, not necessarily the viewport's — callers clip with a scissor
     * when the scaled grid exceeds its viewport. */
    public void render(GuiGraphics guiGraphics, int gridX, int gridY, float zoom, ChunkPos anchor, CellPainter painter) {
        int cs = effectiveCellSize(zoom);
        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                int dx = col - radius;
                int dz = row - radius;
                if (!isWithinRadius(dx, dz)) continue;

                int cellX = gridX + col * cs;
                int cellY = gridY + row * cs;
                long key = new ChunkPos(anchor.x + dx, anchor.z + dz).toLong();

                int tint = painter.tint(dx, dz, key);
                ResourceLocation terrain = painter.texture(key);
                if (terrain != null) {
                    int size = ChronoMapSampler.SIZE;
                    guiGraphics.blit(terrain, cellX, cellY, cs, cs, 0.0F, 0.0F, size, size, size, size);
                    // Status tint over the terrain, translucent so the sampled ground stays visible.
                    guiGraphics.fill(cellX, cellY, cellX + cs, cellY + cs, (0x80 << 24) | (tint & 0xFFFFFF));
                } else {
                    guiGraphics.fill(cellX, cellY, cellX + cs, cellY + cs, tint);
                }
            }
        }
    }

    /** The chunk under (mouseX, mouseY), or null if the cursor isn't over any in-radius cell. */
    @Nullable
    public ChunkPos cellAt(double mouseX, double mouseY, int gridX, int gridY, ChunkPos anchor) {
        return cellAt(mouseX, mouseY, gridX, gridY, 1.0F, anchor);
    }

    /** Zoomed variant of {@link #cellAt}: {@code gridX}/{@code gridY} are the scaled grid's
     * (possibly panned) top-left, matching what was passed to the zoomed {@link #render}. */
    @Nullable
    public ChunkPos cellAt(double mouseX, double mouseY, int gridX, int gridY, float zoom, ChunkPos anchor) {
        int cs = effectiveCellSize(zoom);
        int pixels = gridSize * cs + (gridSize - 1) * CELL_GAP;
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + pixels || mouseY >= gridY + pixels) {
            return null;
        }
        int col = (int) ((mouseX - gridX) / cs);
        int row = (int) ((mouseY - gridY) / cs);
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
        return clampedOffsetAt(mouseX, mouseY, gridX, gridY, 1.0F);
    }

    /** Zoomed variant of {@link #clampedOffsetAt}. */
    public int[] clampedOffsetAt(double mouseX, double mouseY, int gridX, int gridY, float zoom) {
        int cs = effectiveCellSize(zoom);
        int pixels = gridSize * cs + (gridSize - 1) * CELL_GAP;
        double clampedX = Math.min(Math.max(mouseX, gridX), gridX + pixels - 1);
        double clampedY = Math.min(Math.max(mouseY, gridY), gridY + pixels - 1);
        int col = (int) ((clampedX - gridX) / cs);
        int row = (int) ((clampedY - gridY) / cs);
        col = Math.min(Math.max(col, 0), gridSize - 1);
        row = Math.min(Math.max(row, 0), gridSize - 1);
        return new int[] { col - radius, row - radius };
    }

    /** Fills the rectangle of cells spanning (dx1, dz1) to (dx2, dz2), inclusive, in either order. */
    public void renderRectHighlight(GuiGraphics guiGraphics, int gridX, int gridY, int dx1, int dz1, int dx2, int dz2, int color) {
        renderRectHighlight(guiGraphics, gridX, gridY, 1.0F, dx1, dz1, dx2, dz2, color);
    }

    /** Zoomed variant of {@link #renderRectHighlight}. */
    public void renderRectHighlight(GuiGraphics guiGraphics, int gridX, int gridY, float zoom, int dx1, int dz1, int dx2, int dz2, int color) {
        int cs = effectiveCellSize(zoom);
        int minCol = Math.min(dx1, dx2) + radius;
        int maxCol = Math.max(dx1, dx2) + radius;
        int minRow = Math.min(dz1, dz2) + radius;
        int maxRow = Math.max(dz1, dz2) + radius;
        int x1 = gridX + minCol * cs;
        int y1 = gridY + minRow * cs;
        int x2 = gridX + (maxCol + 1) * cs;
        int y2 = gridY + (maxRow + 1) * cs;
        guiGraphics.fill(x1, y1, x2, y2, color);
    }

    public interface CellPainter {
        int tint(int dx, int dz, long chunkKey);

        @Nullable
        ResourceLocation texture(long chunkKey);
    }
}
