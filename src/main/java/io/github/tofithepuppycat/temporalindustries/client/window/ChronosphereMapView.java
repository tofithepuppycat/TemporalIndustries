package io.github.tofithepuppycat.temporalindustries.client.window;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.client.ChunkThumbnailClientState;
import io.github.tofithepuppycat.temporalindustries.client.chunkmap.ChunkSelectionGrid;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/**
 * The Chronosphere's chunk claim map, drawn and driven inside whatever area its host gives it.
 * Claims and releases made while it is open are held locally and sent to the server as one batch
 * when it closes, rather than one packet per chunk touched.
 */
public final class ChronosphereMapView {
    /** How often the terrain thumbnails are re-fetched while open. */
    private static final int MAP_SYNC_INTERVAL_TICKS = 100;

    /** 15px cells rather than the grid's 32px default, so the map fits a window with room around it. */
    private static final int MAP_CELL_SIZE = 15;
    private static final ChunkSelectionGrid MAP_GRID = new ChunkSelectionGrid(
            ChronosphereBlockEntity.MAX_RADIUS, ChronosphereBlockEntity.CLAIM_SHAPE, MAP_CELL_SIZE);
    private static final int TOTAL_CLAIMABLE = countClaimableCells();

    private static final int COLOR_HOME = 0xFFFFD166;
    private static final int COLOR_SELECTED = 0xFF3D9BE0;
    private static final int COLOR_BLOCKED = 0xFFB55050;
    private static final int COLOR_AVAILABLE = 0xFFAFAFAF;
    private static final int COLOR_RECT_PREVIEW = 0x803D9BE0;

    /**
     * Zoom/pan: the viewport stays fixed at the unzoomed grid's footprint (so layout around it
     * doesn't move), while the grid itself scales up and pans within it.
     */
    private static final float MIN_ZOOM = 1.0F;
    private static final float MAX_ZOOM = 3.0F;
    private static final float ZOOM_STEP = 0.25F;

    private final ChunkPos home;
    private final Runnable requestThumbnails;
    private final Consumer<Map<Long, Boolean>> sendToggles;

    private int ticksSinceMapSync = 0;

    /**
     * Drag-to-select state: the direction (claim/release) is fixed to whatever the first cell of the
     * drag did, so dragging a chosen direction across a mixed selection doesn't flip cells back and
     * forth.
     */
    private boolean dragActive = false;
    private boolean dragAdding = false;
    private final Set<Long> dragTouchedChunks = new HashSet<>();
    private final Map<Long, Boolean> pendingChunkToggles = new LinkedHashMap<>();

    /**
     * Ctrl+drag alternative: rather than toggling cells as the cursor crosses them, the whole
     * rectangle between the press point and the release point is applied at once on release.
     */
    private boolean rectDragActive = false;
    private int rectStartDx;
    private int rectStartDz;
    private int rectHoverDx;
    private int rectHoverDz;

    /** The area the host gave the map; the grid is centred horizontally in it and starts at its top. */
    private int areaLeft;
    private int areaTop;
    private int areaWidth;

    private float zoom = MIN_ZOOM;
    private int panX = 0;
    private int panY = 0;
    /** Right-click pan state for the zoomed map. */
    private boolean panDragActive = false;

    /** The map of the Chronosphere at machinePos, with the requests it makes left to the caller. */
    public ChronosphereMapView(BlockPos machinePos, Runnable requestThumbnails, Consumer<Map<Long, Boolean>> sendToggles) {
        this.home = new ChunkPos(machinePos);
        this.requestThumbnails = requestThumbnails;
        this.sendToggles = sendToggles;
    }

    private static int countClaimableCells() {
        int radius = ChronosphereBlockEntity.MAX_RADIUS;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (ChronosphereBlockEntity.isWithinRadius(dx, dz)) count++;
            }
        }
        return count;
    }

    /** Drops the claims and releases not yet sent, for when the repository they apply to is gone. */
    public void discardPendingToggles() {
        pendingChunkToggles.clear();
    }

    public void open() {
        dragActive = false;
        dragTouchedChunks.clear();
        rectDragActive = false;
        panDragActive = false;
        zoom = MIN_ZOOM;
        panX = 0;
        panY = 0;
        ticksSinceMapSync = 0;
        requestThumbnails.run();
    }

    /** Sends the claims and releases still held and drops the terrain thumbnails. */
    public void close() {
        if (!pendingChunkToggles.isEmpty()) {
            sendToggles.accept(pendingChunkToggles);
            pendingChunkToggles.clear();
        }
        ChunkThumbnailClientState.clearAll();
    }

    public void tick() {
        ticksSinceMapSync++;
        if (ticksSinceMapSync >= MAP_SYNC_INTERVAL_TICKS) {
            ticksSinceMapSync = 0;
            requestThumbnails.run();
        }
    }

    /**
     * The claim map's selected view, as if pendingChunkToggles had already been applied; used so
     * dragging reflects instantly without waiting on a server round trip.
     */
    private Set<Long> effectiveSelectedChunks() {
        Set<Long> effective = new HashSet<>(ChronosphereClientState.getSelectedChunks());
        for (Map.Entry<Long, Boolean> entry : pendingChunkToggles.entrySet()) {
            if (entry.getValue()) effective.add(entry.getKey());
            else effective.remove(entry.getKey());
        }
        return effective;
    }

    /** Pixel size of the unzoomed map; hosts size the area they give it from this. */
    public static int gridPixels() {
        return MAP_GRID.gridPixels();
    }

    public static int totalClaimable() {
        return TOTAL_CLAIMABLE;
    }

    public int selectedCount() {
        return effectiveSelectedChunks().size();
    }

    /** Places the map: centred horizontally within width, its top edge at top. */
    public void layout(int left, int top, int width) {
        areaLeft = left;
        areaTop = top;
        areaWidth = width;
    }

    public boolean isOverGrid(double mouseX, double mouseY) {
        return mouseX >= gridX() && mouseX < gridX() + viewportSize() && mouseY >= gridY() && mouseY < gridY() + viewportSize();
    }

    private int gridX() {
        return areaLeft + (areaWidth - MAP_GRID.gridPixels()) / 2;
    }

    private int gridY() {
        return areaTop;
    }

    /** The viewport's fixed footprint: the unzoomed grid size, so the layout around it never moves. */
    private int viewportSize() {
        return MAP_GRID.gridPixels();
    }

    private int maxPan() {
        return Math.max(0, MAP_GRID.gridPixels(zoom) - viewportSize());
    }

    private void clampPan() {
        panX = Math.min(Math.max(panX, 0), maxPan());
        panY = Math.min(Math.max(panY, 0), maxPan());
    }

    /** The scaled grid's top-left, after panning; feeds both rendering and hit-testing. */
    private int zoomedGridX() {
        return gridX() - panX;
    }

    private int zoomedGridY() {
        return gridY() - panY;
    }

    private void adjustZoom(double scrollAmount, double mouseX, double mouseY) {
        float oldZoom = zoom;
        float newZoom = (float) Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom + Math.signum(scrollAmount) * ZOOM_STEP));
        if (newZoom == oldZoom) return;

        // Keep the chunk under the cursor stationary across the zoom change, so scrolling in
        // feels like zooming toward the pointer rather than toward the viewport's corner.
        double anchorXInGrid = mouseX - zoomedGridX();
        double anchorYInGrid = mouseY - zoomedGridY();
        double scale = newZoom / oldZoom;
        zoom = newZoom;
        panX = (int) Math.round(panX + anchorXInGrid * (scale - 1));
        panY = (int) Math.round(panY + anchorYInGrid * (scale - 1));
        clampPan();
    }

    private ChunkPos cellAt(double mouseX, double mouseY) {
        return MAP_GRID.cellAt(mouseX, mouseY, zoomedGridX(), zoomedGridY(), zoom, home);
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Set<Long> effectiveSelected = effectiveSelectedChunks();
        int viewportSize = viewportSize();
        int gridX = gridX();
        int gridY = gridY();

        guiGraphics.enableScissor(gridX, gridY, gridX + viewportSize, gridY + viewportSize);
        MAP_GRID.render(guiGraphics, zoomedGridX(), zoomedGridY(), zoom, home, new ChunkSelectionGrid.CellPainter() {
            @Override
            public int tint(int dx, int dz, long chunkKey) {
                if (dx == 0 && dz == 0) return COLOR_HOME;
                if (effectiveSelected.contains(chunkKey)) return COLOR_SELECTED;
                if (ChronosphereClientState.isBlocked(chunkKey)) return COLOR_BLOCKED;
                return COLOR_AVAILABLE;
            }

            @Override
            public ResourceLocation texture(long chunkKey) {
                return ChunkThumbnailClientState.getTexture(chunkKey);
            }

            @Override
            public boolean emphasized(int dx, int dz, long chunkKey) {
                return (dx == 0 && dz == 0) || effectiveSelected.contains(chunkKey);
            }
        });

        if (isOverGrid(mouseX, mouseY)) {
            MAP_GRID.renderHover(guiGraphics, zoomedGridX(), zoomedGridY(), zoom, mouseX, mouseY);
        }
        if (rectDragActive) {
            MAP_GRID.renderRectHighlight(guiGraphics, zoomedGridX(), zoomedGridY(), zoom,
                    rectStartDx, rectStartDz, rectHoverDx, rectHoverDz, COLOR_RECT_PREVIEW);
        }
        guiGraphics.disableScissor();
        MAP_GRID.renderFrame(guiGraphics, gridX, gridY, viewportSize);
    }

    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (Screen.hasControlDown()) {
                int[] start = MAP_GRID.clampedOffsetAt(mouseX, mouseY, zoomedGridX(), zoomedGridY(), zoom);
                rectStartDx = rectHoverDx = start[0];
                rectStartDz = rectHoverDz = start[1];
                rectDragActive = true;
                dragTouchedChunks.clear();
                long startKey = new ChunkPos(home.x + start[0], home.z + start[1]).toLong();
                dragAdding = !effectiveSelectedChunks().contains(startKey);
            } else {
                ChunkPos clicked = cellAt(mouseX, mouseY);
                if (clicked != null && !clicked.equals(home)) {
                    long key = clicked.toLong();
                    dragActive = true;
                    dragAdding = !effectiveSelectedChunks().contains(key);
                    dragTouchedChunks.clear();
                    applyDragToggle(key);
                }
            }
        } else if (button == 1 && zoom > MIN_ZOOM) {
            panDragActive = true;
        }
    }

    public void mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 1 && panDragActive) {
            panX -= (int) Math.round(dragX);
            panY -= (int) Math.round(dragY);
            clampPan();
        } else if (button == 0 && rectDragActive) {
            int[] hover = MAP_GRID.clampedOffsetAt(mouseX, mouseY, zoomedGridX(), zoomedGridY(), zoom);
            rectHoverDx = hover[0];
            rectHoverDz = hover[1];
        } else if (button == 0 && dragActive) {
            ChunkPos hovered = cellAt(mouseX, mouseY);
            if (hovered != null && !hovered.equals(home)) {
                applyDragToggle(hovered.toLong());
            }
        }
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (rectDragActive) {
            applyRectDrag();
            rectDragActive = false;
        }
        dragActive = false;
        dragTouchedChunks.clear();
        panDragActive = false;
    }

    public void mouseScrolled(double mouseX, double mouseY, double scrollY) {
        adjustZoom(scrollY, mouseX, mouseY);
    }

    /**
     * Records {@code key}'s claim/release using the drag's fixed direction, once per cell per drag,
     * skipping chunks blocked by another machine when claiming.
     */
    private void applyDragToggle(long key) {
        if (!dragTouchedChunks.add(key)) return;
        if (dragAdding && ChronosphereClientState.isBlocked(key)) return;
        pendingChunkToggles.put(key, dragAdding);
    }

    /** Applies the rectangle between the press and release points, in the direction fixed by the starting cell. */
    private void applyRectDrag() {
        int minDx = Math.min(rectStartDx, rectHoverDx);
        int maxDx = Math.max(rectStartDx, rectHoverDx);
        int minDz = Math.min(rectStartDz, rectHoverDz);
        int maxDz = Math.max(rectStartDz, rectHoverDz);
        for (int dx = minDx; dx <= maxDx; dx++) {
            for (int dz = minDz; dz <= maxDz; dz++) {
                if (dx == 0 && dz == 0) continue; // home chunk isn't toggleable
                if (!MAP_GRID.isWithinRadius(dx, dz)) continue;
                applyDragToggle(new ChunkPos(home.x + dx, home.z + dz).toLong());
            }
        }
    }
}
