package io.github.tofithepuppycat.temporalindustries.client.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.EnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;

/**
 * Renders and drives a machine's commit graph — node layout, pan/zoom, click-to-select, hover
 * tooltip — reading from {@link TimelineProjectionManager}. Shared by ChronovaultScreen and
 * ChronosphereScreen so the graph behaves identically everywhere it appears.
 *
 * <p>Callers own the on-screen rectangle the graph draws into and pass it into every
 * render/interaction call; the widget itself holds only pan/zoom/selection state.
 *
 * <p>One repository = one commit tree, so edges come straight from each node's own
 * {@link CommitNode#parentId()} and there's no separate local-parent map to reconcile. Lineage
 * labels are the repository's real branch names, not synthetic ones invented here.
 */
@SuppressWarnings("null")
public final class TimelineGraphWidget {
    private static final ResourceLocation INNER_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/inner.png");
    private static final int INNER_TEXTURE_SIZE = 256;

    private static final int COLUMN_SPACING = 26;
    private static final int ROW_SPACING = 32;
    /** Game ticks per column; nodes are placed by elapsed time, not position in the commit list. */
    private static final double TICKS_PER_COLUMN = 80.0D;
    /** Maximum horizontal spacing in pixels. */
    private static final double MAX_COLUMN_OFFSET = 12.0D;
    /** Minimum column gap between consecutive commits, so ones only ticks apart don't overlap. */
    private static final double MIN_COLUMN_GAP = 0.4D;
    private static final int BRANCH_X_OFFSET = 6;

    // The commit this chunk's live world currently reflects is ringed by a static green halo.
    private static final int HEAD_HALO_RGB = 0xFF33FF33;
    private static final int HEAD_HALO_MARGIN = 2;

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

    private final List<RenderedCommit> renderedCommits = new ArrayList<>();

    // Layout only needs recomputing when the commit list reference changes, not on every render()
    // call. cachedLayoutCommits is the list identity layout was last computed from.
    private List<CommitNode> cachedLayoutCommits = null;
    private Map<Long, CommitNode> byId = new HashMap<>();
    private Map<Long, Integer> rowById = new HashMap<>();
    private Map<Long, Double> columnById = new HashMap<>();
    /** commitId -> the branch label drawn under it; only branch tips get one, like a git ref. */
    private Map<Long, String> labelById = new HashMap<>();
    /** commitId -> the name of the branch whose line it belongs to, for the hover tooltip. */
    private Map<Long, String> branchNameById = new HashMap<>();
    private Map<Long, Integer> forkOffsetById = new HashMap<>();
    /** Render order: parents always before children, so layout/drawing can read a parent's
     * position while laying out its child. */
    private List<CommitNode> drawOrder = new ArrayList<>();

    // Pan/zoom
    private boolean draggingView = false;
    private boolean viewInitialized = false;
    private double viewOffsetX = 0.0D;
    private double viewOffsetY = 0.0D;
    private double zoom = 1.0D;

    private BlockPos machinePos;

    /** Call from the screen's init(): resets pan/zoom so the view recenters on the current node. */
    public void init(BlockPos machinePos) {
        this.machinePos = machinePos;
        viewInitialized = false;
        draggingView = false;
        viewOffsetX = 0.0D;
        viewOffsetY = 0.0D;
        zoom = 1.0D;
    }

    /** Call from the screen's onClose(): resets pan/zoom so reopening recenters on the current node. */
    public void onClose() {
        viewInitialized = false;
        draggingView = false;
        viewOffsetX = 0.0D;
        viewOffsetY = 0.0D;
        zoom = 1.0D;
    }

    public void render(GuiGraphics guiGraphics, Font font, int graphX, int graphY, int graphWidth, int graphHeight) {
        renderedCommits.clear();

        guiGraphics.blit(INNER_TEXTURE, graphX, graphY, 0, 0, graphWidth, graphHeight, INNER_TEXTURE_SIZE, INNER_TEXTURE_SIZE);

        List<CommitNode> commits = TimelineProjectionManager.getCommits();
        if (commits.isEmpty()) return;

        if (commits != cachedLayoutCommits) {
            computeLayout(commits, TimelineProjectionManager.getBranchTips());
        }
        if (drawOrder.isEmpty()) return;

        long headCommitId = TimelineProjectionManager.getHeadCommitId();

        if (!viewInitialized) {
            CommitNode centerOn = byId.getOrDefault(headCommitId, drawOrder.get(drawOrder.size() - 1));
            double centerColumn = columnById.get(centerOn.id());
            int centerRow = rowById.get(centerOn.id());
            viewOffsetX = (graphWidth / 2.0D) - centerColumn * COLUMN_SPACING * zoom;
            viewOffsetY = (graphHeight / 2.0D) - centerRow * ROW_SPACING * zoom;
            viewInitialized = true;
        }

        guiGraphics.enableScissor(graphX, graphY, graphX + graphWidth, graphY + graphHeight);

        // Two passes so lines always render under labels.
        int r = nodeRadius();
        int viewMinX = graphX;
        int viewMinY = graphY;
        int viewMaxX = graphX + graphWidth;
        int viewMaxY = graphY + graphHeight;
        for (CommitNode commit : drawOrder) {
            int pointX = nodeX(graphX, columnById.get(commit.id())) + forkOffsetById.getOrDefault(commit.id(), 0);
            int pointY = nodeY(graphY, rowById.get(commit.id()));

            int lane = rowById.get(commit.id()) % LANE_COLORS.length;
            int laneColor = LANE_COLORS[lane];

            long parentId = commit.parentId();
            CommitNode parent = parentId < 0 ? null : byId.get(parentId);
            if (parent != null) {
                int parentX = nodeX(graphX, columnById.get(parent.id())) + forkOffsetById.getOrDefault(parent.id(), 0);
                int parentY = nodeY(graphY, rowById.get(parent.id()));

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
                boolean selected = commit.id() == TimelineProjectionManager.getSelectedCommitId();
                int color = selected ? 0xFFFFFFFF : laneColor;

                // There's no BRANCH commit type any more (branches are just named tips into the
                // regular tree), so the old hollow-ring branch-marker shape is gone. The remaining
                // shape distinction is author-based: a commit a player made renders as an enlarged
                // diamond, one the machine produced on its own (e.g. a reset's fresh baseline)
                // stays a plain square.
                int shapeRadius = commit.authored() ? markerRadius(r) : r;

                if (commit.id() == headCommitId) {
                    drawHeadHalo(guiGraphics, pointX, pointY, shapeRadius);
                }

                if (commit.authored()) {
                    drawDiamond(guiGraphics, pointX, pointY, shapeRadius, color);
                } else {
                    guiGraphics.fill(pointX - r, pointY - r, pointX + r + 1, pointY + r + 1, color);
                }

                renderedCommits.add(new RenderedCommit(commit, pointX, pointY, shapeRadius));
            }
        }

        // Redraw the selected node's highlight on top, in case a later-drawn sibling overlapped it.
        long selectedCommitId = TimelineProjectionManager.getSelectedCommitId();
        for (RenderedCommit rc : renderedCommits) {
            if (rc.commit.id() != selectedCommitId) {
                continue;
            }
            int selectedColor = 0xFFFFFFFF;
            // Drawn larger too, so the selection is obvious even near a similar lane color.
            int selectedRadius = selectedRadius(rc.hitRadius);
            if (rc.commit.id() == headCommitId) {
                drawHeadHalo(guiGraphics, rc.x, rc.y, selectedRadius);
            }
            if (rc.commit.authored()) {
                drawDiamond(guiGraphics, rc.x, rc.y, selectedRadius, selectedColor);
            } else {
                guiGraphics.fill(rc.x - selectedRadius, rc.y - selectedRadius, rc.x + selectedRadius + 1, rc.y + selectedRadius + 1, selectedColor);
            }
            break;
        }

        for (CommitNode commit : drawOrder) {
            String label = labelById.get(commit.id());
            if (label == null) {
                continue;
            }
            int pointX = nodeX(graphX, columnById.get(commit.id())) + forkOffsetById.getOrDefault(commit.id(), 0);
            int pointY = nodeY(graphY, rowById.get(commit.id()));
            if (pointX < viewMinX || pointX > viewMaxX || pointY < viewMinY || pointY > viewMaxY) {
                continue;
            }
            drawScaledLabel(guiGraphics, font, Component.literal(label), pointX, pointY + r + 3, 0xFFBFBFBF, 1.0F);
        }

        guiGraphics.disableScissor();
    }

    /**
     * Lays out one real parent-child tree. Each commit gets a lane (row): a parent's first child
     * continues its lane, extra children fork into fresh ones. Walking the tree top-down (rather
     * than iterating the commit list in arbitrary order) is what lets the single parentId edge
     * replace the old separate local-parents map.
     */
    private void computeLayout(List<CommitNode> commits, Map<String, Long> branchTips) {
        byId = new HashMap<>();
        rowById = new HashMap<>();
        columnById = new HashMap<>();
        labelById = new HashMap<>();
        branchNameById = new HashMap<>();
        forkOffsetById = new HashMap<>();
        drawOrder = new ArrayList<>();

        for (CommitNode commit : commits) byId.put(commit.id(), commit);

        Map<Long, List<CommitNode>> childrenByParent = new HashMap<>();
        List<CommitNode> roots = new ArrayList<>();
        for (CommitNode commit : commits) {
            CommitNode parent = commit.parentId() < 0 ? null : byId.get(commit.parentId());
            if (parent == null) {
                roots.add(commit);
            } else {
                childrenByParent.computeIfAbsent(commit.parentId(), k -> new ArrayList<>()).add(commit);
            }
        }
        // Deterministic ordering, so a node doesn't hop lanes between polls.
        roots.sort(Comparator.comparingLong(CommitNode::id));
        for (List<CommitNode> children : childrenByParent.values()) {
            children.sort(Comparator.comparingLong(CommitNode::id));
        }

        long baseGameTime = Long.MAX_VALUE;
        for (CommitNode commit : commits) baseGameTime = Math.min(baseGameTime, commit.gameTime());

        int[] nextRow = {0};
        for (CommitNode root : roots) {
            layoutSubtree(root, null, true, childrenByParent, baseGameTime, nextRow);
        }

        assignBranchNames(branchTips);
        for (Map.Entry<String, Long> entry : branchTips.entrySet()) {
            // Only tips get a drawn label, so a branch reads like a git ref pinned to its head
            // rather than labelling every commit on the line.
            if (byId.containsKey(entry.getValue())) labelById.put(entry.getValue(), entry.getKey());
        }

        cachedLayoutCommits = commits;
    }

    private void layoutSubtree(CommitNode commit, CommitNode parent, boolean continuesParentLane,
                               Map<Long, List<CommitNode>> childrenByParent, long baseGameTime, int[] nextRow) {
        int row;
        int forkOffset;
        if (parent == null) {
            row = nextRow[0]++;
            forkOffset = 0;
        } else {
            int parentRow = rowById.getOrDefault(parent.id(), 0);
            int parentOffset = forkOffsetById.getOrDefault(parent.id(), 0);
            row = continuesParentLane ? parentRow : nextRow[0]++;
            forkOffset = continuesParentLane ? parentOffset : parentOffset + BRANCH_X_OFFSET;
        }
        rowById.put(commit.id(), row);
        forkOffsetById.put(commit.id(), forkOffset);

        // Saturating rather than linear so a lineage stale for a long real-time gap doesn't strand
        // its next node far off; eases toward MAX_COLUMN_OFFSET without ever flattening.
        double elapsedTicks = commit.gameTime() - baseGameTime;
        double column = MAX_COLUMN_OFFSET * elapsedTicks / (elapsedTicks + MAX_COLUMN_OFFSET * TICKS_PER_COLUMN);
        if (parent != null) {
            column = Math.max(column, columnById.get(parent.id()) + MIN_COLUMN_GAP);
        }
        columnById.put(commit.id(), column);
        drawOrder.add(commit);

        List<CommitNode> children = childrenByParent.getOrDefault(commit.id(), List.of());
        for (int i = 0; i < children.size(); i++) {
            layoutSubtree(children.get(i), commit, i == 0, childrenByParent, baseGameTime, nextRow);
        }
    }

    /**
     * Attributes every commit to a branch name for the hover tooltip, by walking each tip's
     * ancestry and claiming whatever isn't claimed yet. Tips are visited oldest-first (ascending
     * id), so shared trunk history belongs to the branch that created it and a later fork only
     * owns the commits unique to it — the same reading a git log gives.
     */
    private void assignBranchNames(Map<String, Long> branchTips) {
        List<Map.Entry<String, Long>> tips = new ArrayList<>(branchTips.entrySet());
        tips.sort(Map.Entry.comparingByValue());

        for (Map.Entry<String, Long> tip : tips) {
            long cur = tip.getValue();
            while (cur >= 0 && byId.containsKey(cur) && !branchNameById.containsKey(cur)) {
                branchNameById.put(cur, tip.getKey());
                cur = byId.get(cur).parentId();
            }
        }
    }

    private int nodeRadius() {
        return Math.max(1, (int) Math.round(2 * zoom));
    }

    /** Manual save-point diamonds render larger than a plain node so they stand out against automatic squares. */
    private static int markerRadius(int nodeRadius) {
        return nodeRadius + Math.max(1, nodeRadius / 2) + 1;
    }

    /** Grows a node's radius for the selection redraw, so it reads clearly against neighboring lanes. */
    private static int selectedRadius(int baseRadius) {
        return baseRadius + Math.max(1, baseRadius / 2);
    }

    /** Draws a static green halo behind a node, marking the commit the live world currently reflects. */
    private static void drawHeadHalo(GuiGraphics guiGraphics, int pointX, int pointY, int shapeRadius) {
        int halo = shapeRadius + HEAD_HALO_MARGIN;
        guiGraphics.fill(pointX - halo, pointY - halo, pointX + halo + 1, pointY + halo + 1, HEAD_HALO_RGB);
    }

    /** Draws a filled diamond centered on (pointX, pointY), one shrinking horizontal span per row. */
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

    /** Draws a staircase line as one filled rectangle per row/column crossed, instead of per pixel. */
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

    /** @return true if the click landed inside the graph rect (consumed as a node selection or pan drag start). */
    public boolean mouseClicked(double mouseX, double mouseY, int graphX, int graphY, int graphWidth, int graphHeight) {
        if (!isInGraphArea(mouseX, mouseY, graphX, graphY, graphWidth, graphHeight)) {
            return false;
        }
        RenderedCommit clicked = getCommitAt((int) mouseX, (int) mouseY);
        if (clicked != null) {
            if (clicked.commit.id() == TimelineProjectionManager.getSelectedCommitId()) {
                TimelineProjectionManager.clearSelectedCommit();
            } else {
                TimelineProjectionManager.setSelectedCommit(clicked.commit.id());
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

        // Keep the graph point under the cursor fixed on screen while zooming.
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

    /** Full hover tooltip for whichever node is under the cursor, or empty if none. */
    public List<FormattedCharSequence> getTooltipAt(int mouseX, int mouseY) {
        RenderedCommit hovered = getCommitAt(mouseX, mouseY);
        if (hovered == null) return List.of();

        CommitNode commit = hovered.commit;
        String branchName = branchNameById.get(commit.id());
        List<Component> tooltip = new ArrayList<>();
        if (branchName != null) {
            tooltip.add(Component.literal(branchName + " Timeline"));
        }
        tooltip.add(Component.literal("Commit: " + commit.shortHash()));
        tooltip.add(Component.literal("Recorded: " + formatGameDayTime(commit.gameTime())));
        tooltip.add(Component.literal("Chunks: " + commit.chunkCount()));
        if (commit.hasFullBaseline()) {
            tooltip.add(Component.literal("Includes a full baseline"));
        }
        if (commit.blockChangeCount() > 0) {
            tooltip.add(Component.literal("Changes: " + commit.blockChangeCount()));
        }
        if (TimelineProjectionManager.isBranchTip(commit.id())) {
            tooltip.add(Component.literal("Branch tip"));
        }
        OptionalLong jumpCost = TimelineProjectionManager.getJumpCost(commit.id());
        if (jumpCost.isPresent()) {
            tooltip.add(Component.literal("Jump cost: " + EnergyDisplay.format(jumpCost.getAsLong()) + " FE"));
        }
        return tooltip.stream().map(Component::getVisualOrderText).toList();
    }

    /** Formats an absolute game time as "Day {day} | {HH:MM}"; tick 0 within a day is 06:00. */
    private static String formatGameDayTime(long gameTime) {
        long day = Math.floorDiv(gameTime, 24000L) + 1L;
        long dayTicks = Math.floorMod(gameTime, 24000L);
        long hour = ((dayTicks / 1000L) + 6L) % 24L;
        long minute = (dayTicks % 1000L) * 60L / 1000L;
        return String.format("Day %d | %02d:%02d", day, hour, minute);
    }

    private record RenderedCommit(CommitNode commit, int x, int y, int hitRadius) {}
}
