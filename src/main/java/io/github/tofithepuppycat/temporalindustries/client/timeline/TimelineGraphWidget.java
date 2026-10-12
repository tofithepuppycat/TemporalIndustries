package io.github.tofithepuppycat.temporalindustries.client.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.ShortEnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
import io.github.tofithepuppycat.temporalindustries.timeline.JumpCostBreakdown;

/**
 * Renders and drives a machine's commit graph — node layout, pan/zoom, click-to-select, hover
 * tooltip — reading from {@link TimelineProjectionManager}.
 */
@SuppressWarnings("null")
public final class TimelineGraphWidget {
    private static final ResourceLocation INNER_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/inner.png");
    private static final int INNER_TEXTURE_SIZE = 256;

    private static final int COLUMN_SPACING = 26;
    private static final int ROW_SPACING = 32;
    /**
     * Game ticks per column; nodes are placed by elapsed time, not position in the commit list.
     */
    private static final double TICKS_PER_COLUMN = 80.0D;
    /** Maximum horizontal spacing in pixels. */
    private static final double MAX_COLUMN_OFFSET = 12.0D;
    /** Minimum column gap between consecutive commits, so ones only ticks apart don't overlap. */
    private static final double MIN_COLUMN_GAP = 0.4D;
    private static final int BRANCH_X_OFFSET = 6;

    // The commit this chunk's live world currently reflects is ringed by a softly pulsing green halo.
    private static final int HEAD_HALO_RGB = 0xFF33FF66;
    private static final long HEAD_PULSE_PERIOD_MS = 1600L;
    private static final int NODE_OUTLINE_RGB = 0xFF0E0F18;
    private static final int GRID_DOT_ARGB = 0x16FFFFFF;
    private static final int GRID_SPACING = 20;

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
    /**
     * Render order: parents always before children, so layout/drawing can read a parent's position
     * while laying out its child.
     */
    private List<CommitNode> drawOrder = new ArrayList<>();

    // Pan/zoom
    private boolean draggingView = false;
    private boolean viewInitialized = false;
    private double viewOffsetX = 0.0D;
    private double viewOffsetY = 0.0D;
    private double zoom = 1.0D;

    private BlockPos machinePos;

    /**
     * Call from the screen's init(): resets pan/zoom so the view recenters on the current node.
     */
    public void init(BlockPos machinePos) {
        this.machinePos = machinePos;
        viewInitialized = false;
        draggingView = false;
        viewOffsetX = 0.0D;
        viewOffsetY = 0.0D;
        zoom = 1.0D;
    }

    /**
     * Call from the screen's onClose(): resets pan/zoom so reopening recenters on the current node.
     */
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
        drawGrid(guiGraphics, graphX, graphY, graphWidth, graphHeight);
        guiGraphics.flush();

        float r = nodeRadius();
        int viewMinX = graphX;
        int viewMinY = graphY;
        int viewMaxX = graphX + graphWidth;
        int viewMaxY = graphY + graphHeight;
        long selectedCommitId = TimelineProjectionManager.getSelectedCommitId();
        float lineHalfWidth = (float) Math.max(0.6D, 0.8D * zoom);

        Mesh edges = new Mesh(guiGraphics.pose().last().pose());
        for (CommitNode commit : drawOrder) {
            int pointX = nodeX(graphX, columnById.get(commit.id())) + forkOffsetById.getOrDefault(commit.id(), 0);
            int pointY = nodeY(graphY, rowById.get(commit.id()));
            int laneColor = LANE_COLORS[rowById.get(commit.id()) % LANE_COLORS.length];

            long parentId = commit.parentId();
            CommitNode parent = parentId < 0 ? null : byId.get(parentId);
            if (parent != null) {
                int parentX = nodeX(graphX, columnById.get(parent.id())) + forkOffsetById.getOrDefault(parent.id(), 0);
                int parentY = nodeY(graphY, rowById.get(parent.id()));
                int parentColor = LANE_COLORS[rowById.get(parent.id()) % LANE_COLORS.length];

                // Skip edges entirely outside the viewport rather than tessellating off-screen.
                boolean lineVisible = Math.max(parentX, pointX) >= viewMinX && Math.min(parentX, pointX) <= viewMaxX
                        && Math.max(parentY, pointY) >= viewMinY && Math.min(parentY, pointY) <= viewMaxY;
                if (lineVisible) {
                    edges.curve(parentX, parentY, pointX, pointY, lineHalfWidth, parentColor, laneColor);
                }
            }

            boolean nodeVisible = pointX + r + 6 >= viewMinX && pointX - r - 6 <= viewMaxX
                    && pointY + r + 6 >= viewMinY && pointY - r - 6 <= viewMaxY;
            if (nodeVisible) {
                // Branches are just named tips into one tree, so the only shape distinction is
                // author-based: a commit a player made is an enlarged diamond, one the machine
                // produced on its own (e.g. a reset's fresh baseline) is a plain circle.
                float shapeRadius = commit.authored() ? markerRadius(r) : r;
                int color = commit.id() == selectedCommitId ? 0xFFFFFFFF : laneColor;
                renderedCommits.add(new RenderedCommit(commit, pointX, pointY, (int) Math.ceil(shapeRadius), shapeRadius, color, laneColor));
            }
        }

        beginRawDraw();
        edges.draw();

        double pulse = 0.5D + 0.5D * Math.sin(2.0D * Math.PI * (System.currentTimeMillis() % HEAD_PULSE_PERIOD_MS) / HEAD_PULSE_PERIOD_MS);

        Mesh nodes = new Mesh(guiGraphics.pose().last().pose());
        RenderedCommit selected = null;
        for (RenderedCommit rc : renderedCommits) {
            if (rc.commit.id() == selectedCommitId) {
                selected = rc;
                continue;
            }
            drawNode(nodes, rc, rc.shapeRadius, rc.color, rc.commit.id() == headCommitId, pulse);
        }
        // Drawn last so a later-drawn sibling can't overlap the selection.
        if (selected != null) {
            float radius = selectedRadius(selected.shapeRadius);
            drawNode(nodes, selected, radius, 0xFFFFFFFF, selected.commit.id() == headCommitId, pulse);
            nodes.ring(selected.x, selected.y, radius + 2.5F, radius + 3.5F, 0xCCFFFFFF);
        }
        nodes.draw();
        endRawDraw();

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
            int laneColor = LANE_COLORS[rowById.get(commit.id()) % LANE_COLORS.length];
            drawLabelPill(guiGraphics, font, Component.literal(label), pointX, pointY + (int) Math.ceil(r) + 5, laneColor);
        }

        guiGraphics.disableScissor();
    }

    private static void beginRawDraw() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void endRawDraw() {
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * Faint dot grid that scrolls and scales with the view, giving the pan/zoom a visible frame of
     * reference.
     */
    private void drawGrid(GuiGraphics guiGraphics, int graphX, int graphY, int graphWidth, int graphHeight) {
        double spacing = Math.max(8.0D, GRID_SPACING * zoom);
        double startX = graphX + ((viewOffsetX % spacing) + spacing) % spacing;
        double startY = graphY + ((viewOffsetY % spacing) + spacing) % spacing;
        for (double gx = startX; gx < graphX + graphWidth; gx += spacing) {
            for (double gy = startY; gy < graphY + graphHeight; gy += spacing) {
                int x = (int) gx;
                int y = (int) gy;
                guiGraphics.fill(x, y, x + 1, y + 1, GRID_DOT_ARGB);
            }
        }
    }

    /**
     * Soft glow, dark outline, then the lane-colored body with a lighter core, so nodes read
     * against any lane color.
     */
    private static void drawNode(Mesh mesh, RenderedCommit rc, float radius, int color, boolean head, double pulse) {
        float x = rc.x;
        float y = rc.y;

        if (head) {
            float haloRadius = radius + 4.0F + (float) pulse * 2.0F;
            mesh.circle(x, y, haloRadius + 3.0F, withAlpha(HEAD_HALO_RGB, 0x50), withAlpha(HEAD_HALO_RGB, 0x00));
            mesh.ring(x, y, haloRadius - 0.5F, haloRadius + 0.5F, withAlpha(HEAD_HALO_RGB, 0x90 + (int) (pulse * 0x6F)));
        }
        mesh.circle(x, y, radius + 4.0F, withAlpha(color, 0x38), withAlpha(color, 0x00));

        int core = lighten(color, 0.55F);
        if (rc.commit.authored()) {
            mesh.diamond(x, y, radius + 1.0F, NODE_OUTLINE_RGB);
            mesh.diamond(x, y, radius, color);
            mesh.diamond(x, y, radius * 0.45F, core);
        } else {
            mesh.square(x, y, radius + 1.0F, NODE_OUTLINE_RGB);
            mesh.square(x, y, radius, color);
            mesh.square(x, y, radius * 0.45F, core);
        }
    }

    private static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
    }

    private static int lighten(int argb, float amount) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int c1, int c2, float t) {
        int r = (int) (((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
        int g = (int) (((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
        int b = (int) ((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Batches colored triangles into one buffer so a whole layer of shapes costs a single draw
     * call.
     */
    private static final class Mesh {
        private final BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        private final Matrix4f pose;
        private boolean empty = true;

        Mesh(Matrix4f pose) {
            this.pose = new Matrix4f(pose);
        }

        private void tri(float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
            buffer.addVertex(pose, x1, y1, 0.0F).setColor(c1);
            buffer.addVertex(pose, x2, y2, 0.0F).setColor(c2);
            buffer.addVertex(pose, x3, y3, 0.0F).setColor(c3);
            empty = false;
        }

        void draw() {
            if (empty) return;
            MeshData data = buffer.build();
            if (data != null) BufferUploader.drawWithShader(data);
        }

        private static int segmentsFor(float radius) {
            return Math.max(12, Math.min(40, (int) (radius * 3.0F) + 8));
        }

        /** Radial-gradient disc: centerColor fading to edgeColor. */
        void circle(float cx, float cy, float radius, int centerColor, int edgeColor) {
            int n = segmentsFor(radius);
            for (int i = 0; i < n; i++) {
                double a1 = 2.0D * Math.PI * i / n;
                double a2 = 2.0D * Math.PI * (i + 1) / n;
                tri(cx, cy, centerColor,
                        cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius, edgeColor,
                        cx + (float) Math.cos(a2) * radius, cy + (float) Math.sin(a2) * radius, edgeColor);
            }
        }

        void ring(float cx, float cy, float innerRadius, float outerRadius, int color) {
            int n = segmentsFor(outerRadius);
            for (int i = 0; i < n; i++) {
                double a1 = 2.0D * Math.PI * i / n;
                double a2 = 2.0D * Math.PI * (i + 1) / n;
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                float c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);
                tri(cx + c1 * innerRadius, cy + s1 * innerRadius, color,
                        cx + c1 * outerRadius, cy + s1 * outerRadius, color,
                        cx + c2 * outerRadius, cy + s2 * outerRadius, color);
                tri(cx + c1 * innerRadius, cy + s1 * innerRadius, color,
                        cx + c2 * outerRadius, cy + s2 * outerRadius, color,
                        cx + c2 * innerRadius, cy + s2 * innerRadius, color);
            }
        }

        /** Axis-aligned square spanning cx ± half, cy ± half. */
        void square(float cx, float cy, float half, int color) {
            tri(cx - half, cy - half, color, cx + half, cy - half, color, cx + half, cy + half, color);
            tri(cx - half, cy - half, color, cx + half, cy + half, color, cx - half, cy + half, color);
        }

        void diamond(float cx, float cy, float radius, int color) {
            float reach = radius * 1.2F;
            tri(cx, cy - reach, color, cx + reach, cy, color, cx, cy + reach, color);
            tri(cx, cy - reach, color, cx, cy + reach, color, cx - reach, cy, color);
        }

        /** A thick segment with a color gradient from (x1, y1) to (x2, y2). */
        void segment(float x1, float y1, float x2, float y2, float halfWidth, int c1, int c2) {
            float dx = x2 - x1;
            float dy = y2 - y1;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1.0E-4F) return;
            float nx = -dy / len * halfWidth;
            float ny = dx / len * halfWidth;
            tri(x1 + nx, y1 + ny, c1, x1 - nx, y1 - ny, c1, x2 - nx, y2 - ny, c2);
            tri(x1 + nx, y1 + ny, c1, x2 - nx, y2 - ny, c2, x2 + nx, y2 + ny, c2);
        }

        /**
         * An S-curve between two nodes (smoothstep in y along x) over a faint wider glow stroke.
         */
        void curve(float x1, float y1, float x2, float y2, float halfWidth, int c1, int c2) {
            int n = y1 == y2 ? 1 : Math.max(4, Math.min(28, (int) (Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 4.0F)));
            for (int pass = 0; pass < 2; pass++) {
                float w = pass == 0 ? halfWidth * 2.6F : halfWidth;
                int alpha = pass == 0 ? 0x28 : 0xE6;
                float px = x1;
                float py = y1;
                for (int i = 1; i <= n; i++) {
                    float t = (float) i / n;
                    float eased = t * t * (3.0F - 2.0F * t);
                    float cx = x1 + (x2 - x1) * t;
                    float cy = y1 + (y2 - y1) * eased;
                    segment(px, py, cx, cy, w,
                            withAlpha(mix(c1, c2, (float) (i - 1) / n), alpha),
                            withAlpha(mix(c1, c2, t), alpha));
                    px = cx;
                    py = cy;
                }
            }
        }
    }

    /** Lays out one real parent-child tree. */
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
     * ancestry and claiming whatever isn't claimed yet.
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

    private float nodeRadius() {
        return (float) Math.max(1.5D, 2.6D * zoom);
    }

    /**
     * Manual save-point diamonds render larger than a plain node so they stand out against
     * automatic circles.
     */
    private static float markerRadius(float nodeRadius) {
        return nodeRadius * 1.35F + 1.0F;
    }

    /**
     * Grows a node's radius for the selection redraw, so it reads clearly against neighboring
     * lanes.
     */
    private static float selectedRadius(float baseRadius) {
        return baseRadius * 1.3F + 0.5F;
    }

    /**
     * Draws label centered under (centerX, topY) on a translucent dark pill, text tinted with the
     * lane color.
     */
    private void drawLabelPill(GuiGraphics guiGraphics, Font font, Component label, int centerX, int topY, int color) {
        float scale = (float) zoom;
        float halfWidth = font.width(label) * scale / 2.0F;
        int padX = Math.max(2, Math.round(3 * scale));
        int padY = Math.max(1, Math.round(1.5F * scale));
        int left = Math.round(centerX - halfWidth) - padX;
        int right = Math.round(centerX + halfWidth) + padX;
        int bottom = topY + Math.round(font.lineHeight * scale) + padY * 2 - 1;
        guiGraphics.fill(left + 1, topY, right - 1, bottom, 0xB0101018);
        guiGraphics.fill(left, topY + 1, right, bottom - 1, 0xB0101018);
        guiGraphics.fill(left + 1, topY, right - 1, topY + 1, withAlpha(color, 0x90));

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerX - halfWidth, topY + padY, 0.0D);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(font, label, 0, 0, lighten(color, 0.35F), false);
        guiGraphics.pose().popPose();
    }

    private int nodeX(int graphX, double column) {
        return graphX + 12 + (int) Math.round(column * COLUMN_SPACING * zoom + viewOffsetX);
    }

    private int nodeY(int graphY, int row) {
        return graphY + (int) Math.round(row * ROW_SPACING * zoom + viewOffsetY);
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
            tooltip.add(Component.literal(branchName + " Timeline").withStyle(Style.EMPTY.withColor(hovered.laneColor & 0xFFFFFF)));
        }
        boolean detailed = Screen.hasShiftDown();
        tooltip.add(labeled("Recorded: ", formatGameDayTime(commit.gameTime()), ChatFormatting.WHITE));
        tooltip.add(Component.literal("----------------").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(labeled("Chunks: ", String.valueOf(commit.chunkCount()), ChatFormatting.WHITE));
        if (commit.blockChangeCount() > 0) {
            tooltip.add(labeled("Changes: ", String.valueOf(commit.blockChangeCount()), ChatFormatting.WHITE));
        }
        Optional<JumpCostBreakdown> jumpCost = TimelineProjectionManager.getJumpCost(commit.id());
        if (jumpCost.isPresent()) {
            JumpCostBreakdown cost = jumpCost.get();
            tooltip.add(labeled("Jump cost: ", ShortEnergyDisplay.format(cost.total()), ChatFormatting.YELLOW));
            if (detailed) {
                tooltip.add(formula("Time", COST_TIME_COLOR, String.format("%s x ln(1 + %.1f min) = %s",
                        ShortEnergyDisplay.formatPlain(Math.round(AbstractTimelineMachineBlockEntity.JUMP_TIME_FE_SCALE)),
                        cost.minutes(), ShortEnergyDisplay.format(cost.time()))));
                tooltip.add(formula("Blocks", COST_BLOCKS_COLOR, String.format("sum(%d jump costs) = %s",
                        cost.changedBlocks(), ShortEnergyDisplay.format(cost.blocks()))));
                tooltip.add(formula("Entropy", COST_ENTROPY_COLOR,
                        String.format("%.2f x (e^(%.0f x %.2f) - 1) = %s",
                        AbstractTimelineMachineBlockEntity.JUMP_ENTROPIC_FE_SCALE,
                        AbstractTimelineMachineBlockEntity.JUMP_ENTROPIC_GROWTH, cost.chaos(),
                        ShortEnergyDisplay.format(cost.entropy()))));
            }
        }
        if (detailed) {
            tooltip.add(labeled("Commit: ", commit.shortHash(), ChatFormatting.DARK_AQUA));
        } else {
            tooltip.add(Component.literal("Hold SHIFT for more info").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
        return tooltip.stream().map(Component::getVisualOrderText).toList();
    }

    private static final ChatFormatting COST_TIME_COLOR = ChatFormatting.AQUA;
    private static final ChatFormatting COST_BLOCKS_COLOR = ChatFormatting.GREEN;
    private static final ChatFormatting COST_ENTROPY_COLOR = ChatFormatting.LIGHT_PURPLE;

    private static Component labeled(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(valueColor));
    }

    private static Component formula(String term, ChatFormatting termColor, String body) {
        return Component.literal("  ")
                .append(Component.literal(term).withStyle(termColor))
                .append(Component.literal(" = " + body).withStyle(ChatFormatting.GRAY));
    }

    /** Formats an absolute game time as "Day {day} | {HH:MM}"; tick 0 within a day is 06:00. */
    private static String formatGameDayTime(long gameTime) {
        long day = Math.floorDiv(gameTime, 24000L) + 1L;
        long dayTicks = Math.floorMod(gameTime, 24000L);
        long hour = ((dayTicks / 1000L) + 6L) % 24L;
        long minute = (dayTicks % 1000L) * 60L / 1000L;
        return String.format("Day %d | %02d:%02d", day, hour, minute);
    }

    private record RenderedCommit(CommitNode commit, int x, int y, int hitRadius, float shapeRadius, int color, int laneColor) {}
}
