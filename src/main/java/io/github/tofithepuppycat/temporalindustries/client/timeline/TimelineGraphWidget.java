package io.github.tofithepuppycat.temporalindustries.client.timeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;

/**
 * Renders and drives a machine's commit graph — node layout, pan/zoom, click-to-select, hover
 * tooltip — reading from {@link TimelineProjectionManager}. Shared by every screen that shows
 * "the same timeline view as the Time Machine" ({@code TimeMachineScreen}, {@code ChronosphereScreen}),
 * so the graph looks and behaves identically everywhere it appears; only the surrounding chrome
 * (energy bar, buttons, labels) is screen-specific.
 *
 * <p>Callers own the on-screen rectangle the graph draws into (graphX/Y/Width/Height) and pass it
 * into every render/interaction call — the widget itself holds no layout position, only the pan
 * pan/zoom/selection state for whichever machine is currently active.
 */
@SuppressWarnings("null")
public final class TimelineGraphWidget {
    private static final int COLUMN_SPACING = 26;
    private static final int ROW_SPACING = 32;
    /** Game ticks per column of horizontal spacing — nodes are placed by actual elapsed time
     * rather than by position in the commit list. */
    private static final double TICKS_PER_COLUMN = 80.0D;
    /** Maximum horizontal spacing in pixels */
    private static final double MAX_COLUMN_OFFSET = 12.0D;
    /** Minimum column gap enforced between chronologically consecutive commits, so two commits
     * only ticks apart in gameTime still get visibly separate nodes instead of overlapping. */
    private static final double MIN_COLUMN_GAP = 0.4D;
    private static final int BRANCH_X_OFFSET = 6;

    // Whichever commit this chunk's live world currently reflects blinks: its own node pulses
    // between its lane color and HEAD_HIGHLIGHT_RGB, rather than being ringed by a separate halo.
    private static final int HEAD_HIGHLIGHT_RGB = 0xFFFFFF;
    private static final long HEAD_BLINK_PERIOD_MS = 900L;
    /** How far toward HEAD_HIGHLIGHT_RGB the head node's own color is pulled at the dimmest and
     * brightest points of the blink. Never reaches 0 so the head stays identifiable mid-cycle. */
    private static final float HEAD_BLINK_MIX_MIN = 0.15F;
    private static final float HEAD_BLINK_MIX_MAX = 1.0F;

    private static final double MIN_ZOOM = 0.35D;
    private static final double MAX_ZOOM = 3.0D;

    private static final int[] LANE_COLORS = {
            0xFF66C7FF, // blue
            0xFFFF9640, // orange
            0xFF66FF8C, // green
            0xFFFF66E0, // pink
            0xFFFFD166, // yellow
            0xFF9B6BFF, // purple
            0xFFFF3322, // red
            0xFF4DD0E1, // cyan
            0xFFC0CA33, // lime
            0xFFEC407A, // magenta
            0xFF8D6E63, // brown
            0xFFB0BEC5, // slate
            0xFFFFF176, // pale yellow
            0xFF26A69A, // teal
    };

    /** Lineage display names, cycling Alpha, Beta, Gamma... */
    private static final String[] GREEK_NAMES = {
            "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta",
            "Iota", "Kappa", "Lambda", "Mu", "Nu", "Xi", "Omicron", "Pi",
            "Rho", "Sigma", "Tau", "Upsilon", "Phi", "Chi", "Psi", "Omega",
    };

    private final List<RenderedCommit> renderedCommits = new ArrayList<>();
    /** commitId -> GREEK_NAMES index of its lineage. Rebuilt each render() call. */
    private final Map<Long, Integer> timelineIndexById = new HashMap<>();

    // Layout (row/column/label/fork-offset assignment) only actually needs recomputing when
    // TimelineProjectionManager's commit list reference changes — which, since the server skips
    // replying when nothing changed, is normally just once a second at most — rather than on every
    // render() call (60/sec). cachedLayoutCommits is the identity of the list this layout was last
    // computed from; a mismatch (including the initial null) triggers a rebuild.
    private List<TemporalCommit> cachedLayoutCommits = null;
    private Map<Long, TemporalCommit> byId = new HashMap<>();
    private Map<Long, Integer> rowById = new HashMap<>();
    private Map<Long, Double> columnById = new HashMap<>();
    private Map<Long, String> labelById = new HashMap<>();
    private Map<Long, Integer> forkOffsetById = new HashMap<>();

    // Pan/zoom
    private boolean draggingView = false;
    private boolean viewInitialized = false;
    private double viewOffsetX = 0.0D;
    private double viewOffsetY = 0.0D;
    private double zoom = 1.0D;

    /** Per-machine pan/zoom, kept across GUI close+reopen for this client session. */
    private static final Map<BlockPos, ViewState> savedViewStates = new HashMap<>();

    private record ViewState(double offsetX, double offsetY, double zoom) {}

    private BlockPos machinePos;

    /** Call from the screen's init(): sets which machine's saved pan/zoom to restore. */
    public void init(BlockPos machinePos) {
        this.machinePos = machinePos;
        viewInitialized = false;
        draggingView = false;

        ViewState saved = savedViewStates.get(machinePos);
        if (saved != null) {
            viewOffsetX = saved.offsetX();
            viewOffsetY = saved.offsetY();
            zoom = saved.zoom();
            viewInitialized = true;
        } else {
            viewOffsetX = 0.0D;
            viewOffsetY = 0.0D;
            zoom = 1.0D;
        }
    }

    /** Call from the screen's onClose(): persists pan/zoom for next time this machine is opened. */
    public void onClose() {
        if (machinePos != null) {
            savedViewStates.put(machinePos, new ViewState(viewOffsetX, viewOffsetY, zoom));
        }
    }

    public void render(GuiGraphics guiGraphics, Font font, int graphX, int graphY, int graphWidth, int graphHeight) {
        renderedCommits.clear();

        guiGraphics.fill(graphX, graphY, graphX + graphWidth, graphY + graphHeight, 0xFF000000);

        List<TemporalCommit> commits = TimelineProjectionManager.getCommits();
        if (commits.isEmpty()) return;

        Map<Long, Long> localParentById = TimelineProjectionManager.getLocalParents();

        if (commits != cachedLayoutCommits) {
            computeLayout(commits, localParentById);
        }

        if (!viewInitialized) {
            TemporalCommit latest = commits.get(commits.size() - 1);
            double lastColumn = columnById.get(latest.getId());
            int lastRow = rowById.get(latest.getId());
            viewOffsetX = (graphWidth - 24) - lastColumn * COLUMN_SPACING * zoom;
            viewOffsetY = (graphHeight / 2.0D) - lastRow * ROW_SPACING * zoom;
            viewInitialized = true;
        }

        guiGraphics.enableScissor(graphX, graphY, graphX + graphWidth, graphY + graphHeight);

        long headCommitId = TimelineProjectionManager.getHeadCommitId();
        double headBlinkPhase = (System.currentTimeMillis() % HEAD_BLINK_PERIOD_MS) / (double) HEAD_BLINK_PERIOD_MS;
        float headBlinkMix = HEAD_BLINK_MIX_MIN + (HEAD_BLINK_MIX_MAX - HEAD_BLINK_MIX_MIN)
                * (0.5F + 0.5F * (float) Math.sin(2.0D * Math.PI * headBlinkPhase));

        // Two passes so lines always render under labels.
        int r = nodeRadius();
        int viewMinX = graphX;
        int viewMinY = graphY;
        int viewMaxX = graphX + graphWidth;
        int viewMaxY = graphY + graphHeight;
        for (TemporalCommit commit : commits) {
            int pointX = nodeX(graphX, columnById.get(commit.getId())) + forkOffsetById.getOrDefault(commit.getId(), 0);
            int pointY = nodeY(graphY, rowById.get(commit.getId()));

            int lane = rowById.get(commit.getId()) % LANE_COLORS.length;
            int laneColor = LANE_COLORS[lane];

            long localParentId = localParentById.getOrDefault(commit.getId(), -1L);
            boolean hasParent = localParentId >= 0 && byId.get(localParentId) != null;
            if (hasParent) {
                TemporalCommit parent = byId.get(localParentId);
                int parentX = nodeX(graphX, columnById.get(parent.getId())) + forkOffsetById.getOrDefault(parent.getId(), 0);
                int parentY = nodeY(graphY, rowById.get(parent.getId()));

                // Skip lines entirely outside the viewport rather than rasterizing off-screen.
                boolean lineVisible = Math.max(parentX, pointX) >= viewMinX && Math.min(parentX, pointX) <= viewMaxX
                        && Math.max(parentY, pointY) >= viewMinY && Math.min(parentY, pointY) <= viewMaxY;
                if (lineVisible) {
                    drawLine(guiGraphics, parentX, parentY, pointX, pointY, laneColor);
                }
            }

            boolean nodeVisible = pointX + r >= viewMinX && pointX - r <= viewMaxX
                    && pointY + r >= viewMinY && pointY - r <= viewMaxY;
            if (nodeVisible) {
                boolean selected = commit.getId() == TimelineProjectionManager.getSelectedCommitId();
                boolean isBranch = commit.getType() == TemporalCommit.Type.BRANCH;
                int color = selected ? 0xFFFFFFFF : laneColor;
                if (commit.getId() == headCommitId) {
                    // Always pulses from the LANE color, never from the selected white: blending
                    // white toward white is a no-op, which would freeze the blink for exactly the
                    // node most likely to be selected.
                    color = mixColor(laneColor, 0xFF000000 | HEAD_HIGHLIGHT_RGB, headBlinkMix);
                }

                int shapeRadius = commit.isPlayerMark() ? markerRadius(r) : r;

                if (isBranch) {
                    // Branch points render as a hollow ring so a fork is visually distinct from a plain commit.
                    int outer = r + 1;
                    int inner = Math.max(1, r - 1);
                    guiGraphics.fill(pointX - outer, pointY - outer, pointX + outer + 1, pointY + outer + 1, color);
                    guiGraphics.fill(pointX - inner, pointY - inner, pointX + inner + 1, pointY + inner + 1, 0xFF000000);
                } else if (commit.isPlayerMark()) {
                    // Player-recorded marks render as a diamond, distinct from an automatic commit's square —
                    // enlarged relative to a plain node so a manual bookmark stands out on the graph.
                    drawDiamond(guiGraphics, pointX, pointY, shapeRadius, color);
                } else {
                    guiGraphics.fill(pointX - r, pointY - r, pointX + r + 1, pointY + r + 1, color);
                }

                renderedCommits.add(new RenderedCommit(commit, pointX, pointY, shapeRadius, laneColor));
            }
        }

        // Redraw the selected node's highlight on top, in case a sibling drawn later in
        // chronological order (e.g. a branch marker sharing its parent's gameTime, or a fork nudged
        // only slightly right by BRANCH_X_OFFSET) ended up overlapping and painting over it.
        long selectedCommitId = TimelineProjectionManager.getSelectedCommitId();
        for (RenderedCommit rc : renderedCommits) {
            if (rc.commit.getId() != selectedCommitId) {
                continue;
            }
            // Keeps blinking when the selection happens to BE the head — repainting flat white here
            // would otherwise freeze the pulse for exactly the node most likely to be selected.
            int selectedColor = rc.commit.getId() == headCommitId
                    ? mixColor(rc.laneColor, 0xFF000000 | HEAD_HIGHLIGHT_RGB, headBlinkMix)
                    : 0xFFFFFFFF;
            // Drawn larger than its normal size too, on top of the recolor, so the selection is
            // obvious even when its color happens to be close to a neighboring lane's.
            int selectedRadius = selectedRadius(rc.hitRadius);
            if (rc.commit.getType() == TemporalCommit.Type.BRANCH) {
                int outer = selectedRadius + 1;
                int inner = Math.max(1, selectedRadius - 1);
                guiGraphics.fill(rc.x - outer, rc.y - outer, rc.x + outer + 1, rc.y + outer + 1, selectedColor);
                guiGraphics.fill(rc.x - inner, rc.y - inner, rc.x + inner + 1, rc.y + inner + 1, 0xFF000000);
            } else if (rc.commit.isPlayerMark()) {
                drawDiamond(guiGraphics, rc.x, rc.y, selectedRadius, selectedColor);
            } else {
                guiGraphics.fill(rc.x - selectedRadius, rc.y - selectedRadius, rc.x + selectedRadius + 1, rc.y + selectedRadius + 1, selectedColor);
            }
            break;
        }

        for (TemporalCommit commit : commits) {
            String label = labelById.get(commit.getId());
            if (label == null) {
                continue;
            }
            int pointX = nodeX(graphX, columnById.get(commit.getId())) + forkOffsetById.getOrDefault(commit.getId(), 0);
            int pointY = nodeY(graphY, rowById.get(commit.getId()));
            if (pointX < viewMinX || pointX > viewMaxX || pointY < viewMinY || pointY > viewMaxY) {
                continue;
            }
            drawScaledLabel(guiGraphics, font, Component.literal(label), pointX, pointY + r + 3, 0xFFBFBFBF, 1.0F);
        }

        guiGraphics.disableScissor();
    }

    private void computeLayout(List<TemporalCommit> commits, Map<Long, Long> localParentById) {
        // Assign each commit a lane (row): the first child continues its parent's lane, additional
        // children fork into new lanes. localParentById is this chunk's own fork history, distinct
        // from a commit's dimension-wide getParentId().
        byId = new HashMap<>();
        rowById = new HashMap<>();
        columnById = new HashMap<>();
        labelById = new HashMap<>();
        forkOffsetById = new HashMap<>();
        Map<Long, Integer> childCountByParent = new HashMap<>();
        timelineIndexById.clear();

        long baseGameTime = commits.get(0).getGameTime();
        int nextRow = 1;
        int nextTimelineIndex = 0;
        for (int i = 0; i < commits.size(); i++) {
            TemporalCommit commit = commits.get(i);
            byId.put(commit.getId(), commit);

            long localParentId = localParentById.getOrDefault(commit.getId(), -1L);
            boolean isBranch = commit.getType() == TemporalCommit.Type.BRANCH;
            boolean isRoot = localParentId < 0;

            // The root commit starts its own lineage too, same as any branch point.
            int timelineIndex;
            if (isRoot || isBranch) {
                timelineIndex = nextTimelineIndex++;
            } else {
                timelineIndex = timelineIndexById.getOrDefault(localParentId, 0);
            }
            timelineIndexById.put(commit.getId(), timelineIndex);
            if (isRoot || isBranch) {
                labelById.put(commit.getId(), GREEK_NAMES[timelineIndex % GREEK_NAMES.length] + " Timeline");
            }

            int row;
            if (isRoot) {
                row = 0;
                forkOffsetById.put(commit.getId(), 0);
            } else {
                int parentRow = rowById.getOrDefault(localParentId, 0);
                int childIndex = childCountByParent.getOrDefault(localParentId, 0);
                childCountByParent.put(localParentId, childIndex + 1);
                // Row 0 is reserved for the trunk, so the first checkout off it always gets a new
                // row; re-checking out an existing branch reuses its row instead of forking again.
                boolean forksRow = childIndex != 0 || (isBranch && parentRow == 0);
                row = forksRow ? nextRow++ : parentRow;
                int parentOffset = forkOffsetById.getOrDefault(localParentId, 0);
                forkOffsetById.put(commit.getId(), forksRow ? parentOffset + BRANCH_X_OFFSET : parentOffset);
            }
            rowById.put(commit.getId(), row);
            // Saturating rather than linear or hard-clamped so a lineage that's gone stale for a
            // long real-time gap doesn't strand its next node far off in the distance: matches
            // roughly linear spacing for small gaps (slope at 0 is 1/TICKS_PER_COLUMN) but eases
            // toward MAX_COLUMN_OFFSET for large ones WITHOUT ever truly flattening out — a hard
            // clamp would stack every commit past the cap on the exact same column.
            double elapsedTicks = commit.getGameTime() - baseGameTime;
            double distanceOffset = MAX_COLUMN_OFFSET * elapsedTicks
                    / (elapsedTicks + MAX_COLUMN_OFFSET * TICKS_PER_COLUMN);
            // The minimum gap is enforced against this commit's own local parent rather than
            // whichever commit happens to precede it in (registration-order) commits — a branch
            // marker's gameTime is the historical point it was checked out at, not "now" it was
            // created, so it can easily be earlier than other commits already ahead of it in that
            // list. Comparing to its own parent keeps it pinned near that point in time (as
            // requested) instead of getting dragged rightward to match unrelated later history.
            double column = distanceOffset;
            if (!isRoot) {
                double parentColumn = columnById.get(localParentId);
                column = Math.max(column, parentColumn + MIN_COLUMN_GAP);
            }
            columnById.put(commit.getId(), column);
        }

        cachedLayoutCommits = commits;
    }

    private int nodeRadius() {
        return Math.max(1, (int) Math.round(2 * zoom));
    }

    /** Player-mark diamonds render larger than a plain node's radius so a manual bookmark reads
     * clearly against the automatic squares around it. */
    private static int markerRadius(int nodeRadius) {
        return nodeRadius + Math.max(1, nodeRadius / 2) + 1;
    }

    /** Grows a node's normal on-screen radius for the selection redraw, so the selected node reads
     * clearly even when its (white, or blink-mixed) color ends up close to a neighboring lane's. */
    private static int selectedRadius(int baseRadius) {
        return baseRadius + Math.max(1, baseRadius / 2);
    }

    /** Blends two opaque ARGB colors, mix=0 giving from and mix=1 giving to. Used to pulse the
     * head node's own fill rather than drawing anything extra around it — a translucent overlay
     * would just darken toward the black graph background instead of reading as a blink. */
    private static int mixColor(int from, int to, float mix) {
        float t = Math.max(0.0F, Math.min(1.0F, mix));
        int r = Math.round(((from >> 16) & 0xFF) * (1.0F - t) + ((to >> 16) & 0xFF) * t);
        int g = Math.round(((from >> 8) & 0xFF) * (1.0F - t) + ((to >> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * (1.0F - t) + (to & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Draws a filled diamond (a plus-like rotated square) centered on (pointX, pointY), one
     * shrinking horizontal span per row — same per-row-fill rasterization style as the ring/square
     * shapes above, just diamond-shaped instead. */
    private static void drawDiamond(GuiGraphics guiGraphics, int pointX, int pointY, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int halfWidth = r - Math.abs(dy);
            guiGraphics.fill(pointX - halfWidth, pointY + dy, pointX + halfWidth + 1, pointY + dy + 1, color);
        }
    }

    /** Draws label horizontally centered under (centerX, topY) — topY is where the text starts. */
    private void drawScaledLabel(GuiGraphics guiGraphics, Font font, Component label, int centerX, int topY, int color, float extraScale) {
        float scale = (float) zoom * extraScale;
        float halfWidth = font.width(label) * scale / 2.0F;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerX - halfWidth, topY, 0.0D);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(font, label, 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private int nodeX(int graphX, double column) {
        return graphX + 12 + (int) Math.round(column * COLUMN_SPACING * zoom + viewOffsetX);
    }

    private int nodeY(int graphY, int row) {
        return graphY + (int) Math.round(row * ROW_SPACING * zoom + viewOffsetY);
    }

    /** Draws a staircase line as one filled rectangle per row/column crossed, batched along the
     * minority axis, instead of one fill call per pixel. */
    private static void drawLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int adx = Math.abs(dx);
        int ady = Math.abs(dy);

        if (ady == 0) {
            int rx1 = Math.min(x1, x2);
            int rx2 = Math.max(x1, x2);
            guiGraphics.fill(rx1, y1, rx2 + 1, y1 + 1, color);
            return;
        }
        if (adx == 0) {
            int ry1 = Math.min(y1, y2);
            int ry2 = Math.max(y1, y2);
            guiGraphics.fill(x1, ry1, x1 + 1, ry2 + 1, color);
            return;
        }

        if (adx >= ady) {
            int yStep = dy > 0 ? 1 : -1;
            int prevX = x1;
            for (int row = 1; row <= ady; row++) {
                int y = y1 + (row - 1) * yStep;
                int xAtRowEnd = x1 + (int) Math.round((double) dx * row / ady);
                guiGraphics.fill(Math.min(prevX, xAtRowEnd), y, Math.max(prevX, xAtRowEnd) + 1, y + 1, color);
                prevX = xAtRowEnd;
            }
            guiGraphics.fill(Math.min(prevX, x2), y2, Math.max(prevX, x2) + 1, y2 + 1, color);
        } else {
            int xStep = dx > 0 ? 1 : -1;
            int prevY = y1;
            for (int col = 1; col <= adx; col++) {
                int x = x1 + (col - 1) * xStep;
                int yAtColEnd = y1 + (int) Math.round((double) dy * col / adx);
                guiGraphics.fill(x, Math.min(prevY, yAtColEnd), x + 1, Math.max(prevY, yAtColEnd) + 1, color);
                prevY = yAtColEnd;
            }
            guiGraphics.fill(x2, Math.min(prevY, y2), x2 + 1, Math.max(prevY, y2) + 1, color);
        }
    }

    // -------------------------------------------------------------------------
    // Interaction

    /** @return true if the click landed inside the graph rect (consumed either as a node
     * selection or the start of a pan drag) — mirrors the graph area's isInGraphArea+onClick logic. */
    public boolean mouseClicked(double mouseX, double mouseY, int graphX, int graphY, int graphWidth, int graphHeight) {
        if (!isInGraphArea(mouseX, mouseY, graphX, graphY, graphWidth, graphHeight)) {
            return false;
        }
        RenderedCommit clicked = getCommitAt((int) mouseX, (int) mouseY);
        if (clicked != null) {
            if (clicked.commit.getId() == TimelineProjectionManager.getSelectedCommitId()) {
                TimelineProjectionManager.clearSelectedCommit();
            } else {
                TimelineProjectionManager.setSelectedCommit(clicked.commit.getId());
            }
        } else {
            draggingView = true;
        }
        return true;
    }

    /** @return true if a pan drag is in progress (and was advanced by this call). */
    public boolean mouseDragged(double dragX, double dragY) {
        if (!draggingView) return false;
        viewOffsetX += dragX;
        viewOffsetY += dragY;
        return true;
    }

    /** @return true if a pan drag was in progress (and is now released). */
    public boolean mouseReleased() {
        if (!draggingView) return false;
        draggingView = false;
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY, int graphX, int graphY, int graphWidth, int graphHeight) {
        if (!isInGraphArea(mouseX, mouseY, graphX, graphY, graphWidth, graphHeight)) {
            return false;
        }

        double oldZoom = zoom;
        double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * Math.pow(1.1D, scrollY)));
        if (newZoom == oldZoom) {
            return true;
        }

        // Keep whatever graph point is under the cursor fixed on screen while zooming, instead of
        // zooming around the graph's origin.
        double originX = graphX + 12;
        double originY = graphY;
        double unitX = (mouseX - originX - viewOffsetX) / (COLUMN_SPACING * oldZoom);
        double unitY = (mouseY - originY - viewOffsetY) / (ROW_SPACING * oldZoom);

        zoom = newZoom;
        viewOffsetX = mouseX - originX - unitX * COLUMN_SPACING * zoom;
        viewOffsetY = mouseY - originY - unitY * ROW_SPACING * zoom;
        return true;
    }

    private static boolean isInGraphArea(double mouseX, double mouseY, int graphX, int graphY, int graphWidth, int graphHeight) {
        return mouseX >= graphX && mouseX <= graphX + graphWidth && mouseY >= graphY && mouseY <= graphY + graphHeight;
    }

    private RenderedCommit getCommitAt(int mouseX, int mouseY) {
        for (RenderedCommit rc : renderedCommits) {
            int tolerance = rc.hitRadius + 2;
            if (Math.abs(mouseX - rc.x) <= tolerance && Math.abs(mouseY - rc.y) <= tolerance) return rc;
        }
        return null;
    }

    /** Full hover tooltip for whichever node is under the cursor, or empty if none. Identical
     * across every screen that embeds this widget, so the graph reads the same everywhere. */
    public List<FormattedCharSequence> getTooltipAt(int mouseX, int mouseY) {
        RenderedCommit hovered = getCommitAt(mouseX, mouseY);
        if (hovered == null) return List.of();

        TemporalCommit commit = hovered.commit;
        int timelineIndex = timelineIndexById.getOrDefault(commit.getId(), 0);
        String timelineName = GREEK_NAMES[timelineIndex % GREEK_NAMES.length];
        List<Component> tooltip = new ArrayList<>(List.of(
                Component.literal(timelineName + " Timeline"),
                Component.literal("Commit: " + commit.getShortHash()),
                Component.literal("Recorded: " + formatGameDayTime(commit.getGameTime()))
        ));
        switch (commit.getType()) {
            case BRANCH -> tooltip.add(Component.literal("Branch point"));
            case SNAPSHOT -> tooltip.add(Component.literal("Full snapshot"));
            case DELTA -> tooltip.add(Component.literal("Changes: " + commit.getTotalChangeCount()));
            case PLAYER_MARK -> tooltip.add(Component.literal("Player-recorded snapshot"));
        }
        OptionalLong jumpCost = TimelineProjectionManager.getJumpCost(commit.getId());
        if (jumpCost.isPresent()) {
            tooltip.add(Component.literal("Jump cost: " + jumpCost.getAsLong() + " FE"));
        }
        return tooltip.stream().map(Component::getVisualOrderText).toList();
    }

    /** Formats an absolute game time as "Day {day} | {HH:MM}". Day 1 starts at game time 0; a
     * Minecraft day is 24000 ticks, and tick 0 within a day is 06:00. */
    private static String formatGameDayTime(long gameTime) {
        long day = Math.floorDiv(gameTime, 24000L) + 1L;
        long dayTicks = Math.floorMod(gameTime, 24000L);
        long hour = ((dayTicks / 1000L) + 6L) % 24L;
        long minute = (dayTicks % 1000L) * 60L / 1000L;
        return String.format("Day %d | %02d:%02d", day, hour, minute);
    }

    private static final class RenderedCommit {
        private final TemporalCommit commit;
        private final int x;
        private final int y;
        private final int hitRadius;
        /** This node's lineage color, kept so the selected-node redraw pass can restart the head
         * blink from it rather than from the selection's flat white (see render()). */
        private final int laneColor;

        private RenderedCommit(TemporalCommit commit, int x, int y, int hitRadius, int laneColor) {
            this.commit = commit;
            this.x = x;
            this.y = y;
            this.hitRadius = hitRadius;
            this.laneColor = laneColor;
        }
    }
}
