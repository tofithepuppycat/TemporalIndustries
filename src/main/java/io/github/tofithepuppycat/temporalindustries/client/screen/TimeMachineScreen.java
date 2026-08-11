package io.github.tofithepuppycat.temporalindustries.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.jetbrains.annotations.NotNull;

import com.mojang.blaze3d.systems.RenderSystem;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.menu.TimeMachineMenu;
import io.github.tofithepuppycat.temporalindustries.network.RollbackChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** GUI for the Time Machine block: renders its chunk's commit graph and lets the player pick a
 * point in time to jump to. */
@SuppressWarnings("null")
public class TimeMachineScreen extends AbstractContainerScreen<TimeMachineMenu> {
    private static final ResourceLocation INVENTORY_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/timemachine.png");
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;

    private static final int GRAPH_X_OFFSET = 8;
    private static final int GRAPH_Y_OFFSET = 18;
    private static final int GRAPH_WIDTH = 240;
    private static final int GRAPH_HEIGHT = 178;
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

    // Blinking halo around whichever commit this chunk's live world currently reflects.
    private static final int HEAD_HIGHLIGHT_RGB = 0x00FFC8;
    private static final long HEAD_BLINK_PERIOD_MS = 900L;
    private static final float HEAD_BLINK_ALPHA_MIN = 0.15F;
    private static final float HEAD_BLINK_ALPHA_MAX = 0.9F;

    private static final double MIN_ZOOM = 0.35D;
    private static final double MAX_ZOOM = 3.0D;

    private static final int ENERGY_BAR_X_OFFSET = 160;
    private static final int ENERGY_BAR_Y_OFFSET = 7;
    private static final int ENERGY_BAR_WIDTH = 88;
    private static final int ENERGY_BAR_HEIGHT = 8;

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

    private static final int SYNC_INTERVAL_TICKS = 20;

    private Button jumpButton;
    private Button showChangesButton;
    private int ticksSinceSync = 0;
    private final List<RenderedCommit> renderedCommits = new ArrayList<>();
    /** commitId -> GREEK_NAMES index of its lineage. Rebuilt each renderTimelineGraph call. */
    private final Map<Long, Integer> timelineIndexById = new HashMap<>();

    // Graph layout (row/column/label/fork-offset assignment) only actually needs recomputing when
    // TimelineProjectionManager's commit list reference changes — which, since the server now
    // skips replying when nothing changed, is normally just once a second at most — rather than
    // on every render() call (60/sec). cachedLayoutCommits is the identity of the list this layout
    // was last computed from; a mismatch (including the initial null) triggers a rebuild.
    private List<TemporalCommit> cachedLayoutCommits = null;
    private Map<Long, TemporalCommit> byId = new HashMap<>();
    private Map<Long, Integer> rowById = new HashMap<>();
    private Map<Long, Double> columnById = new HashMap<>();
    private Map<Long, String> labelById = new HashMap<>();
    private Map<Long, Integer> forkOffsetById = new HashMap<>();

    // Node graph pan/zoom
    private boolean draggingView = false;
    private boolean viewInitialized = false;
    private double viewOffsetX = 0.0D;
    private double viewOffsetY = 0.0D;
    private double zoom = 1.0D;

    /** Per-machine pan/zoom, kept across GUI close+reopen for this client session. */
    private static final Map<BlockPos, ViewState> savedViewStates = new HashMap<>();

    private record ViewState(double offsetX, double offsetY, double zoom) {}

    public TimeMachineScreen(TimeMachineMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = TEXTURE_WIDTH;
        imageHeight = TEXTURE_HEIGHT;
        inventoryLabelY = imageHeight + 100; // Push off-screen to hide inventory
    }

    @Override
    protected void init() {
        super.init();

        int buttonY = topPos + 224;
        int buttonWidth = 76;
        int gap = 3;
        int groupX = leftPos + (imageWidth - (buttonWidth * 2 + gap)) / 2;

        showChangesButton = Button.builder(showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()), btn -> toggleShowChanges())
            .pos(groupX, buttonY)
            .size(buttonWidth, 20)
            .build();
        addRenderableWidget(showChangesButton);

        jumpButton = Button.builder(Component.translatable("gui.temporalindustries.time_machine.jump"), btn -> jumpAndClose())
            .pos(groupX + buttonWidth + gap, buttonY)
            .size(buttonWidth, 20)
            .build();
        jumpButton.active = TimelineProjectionManager.hasSelection();
        addRenderableWidget(jumpButton);

        TimelineProjectionManager.setActiveMachine(menu.getBlockPos());
        // Force a full response regardless of whatever TimelineProjectionManager still has
        // cached from a previously viewed machine (Long.MIN_VALUE can never equal a real head id).
        PacketDistributor.sendToServer(new TimelinePreviewRequestPacket(menu.getBlockPos(), Long.MIN_VALUE));

        ViewState saved = savedViewStates.get(menu.getBlockPos());
        if (saved != null) {
            viewOffsetX = saved.offsetX();
            viewOffsetY = saved.offsetY();
            zoom = saved.zoom();
            viewInitialized = true;
        }
    }

    @Override
    public void onClose() {
        savedViewStates.put(menu.getBlockPos(), new ViewState(viewOffsetX, viewOffsetY, zoom));
        super.onClose();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        TimelineProjectionManager.setCurrentGameTime(getCurrentGameTime());

        // Periodically re-fetch commits so new ones show up while the GUI is open. The server
        // skips the (potentially large, ever-growing) full commit list reply entirely when the
        // chunk's head hasn't moved since our last known value, so this is cheap on the common
        // case of nothing having changed in the last second.
        ticksSinceSync++;
        if (ticksSinceSync >= SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            PacketDistributor.sendToServer(
                    new TimelinePreviewRequestPacket(menu.getBlockPos(), TimelineProjectionManager.getHeadCommitId()));
        }

        jumpButton.active = TimelineProjectionManager.hasSelection();
    }

    private void selectCommit(TemporalCommit commit) {
        TimelineProjectionManager.setSelectedCommit(commit.getId());
    }

    private void toggleShowChanges() {
        TimelineProjectionManager.toggleShowChanges();
        showChangesButton.setMessage(showChangesLabel(TimelineProjectionManager.isShowChangesEnabled()));
    }

    private void jumpAndClose() {
        if (!TimelineProjectionManager.hasSelection()) {
            return;
        }
        long target = TimelineProjectionManager.getSelectedGameTime();
        PacketDistributor.sendToServer(new RollbackChunkPacket(menu.getBlockPos(), target));
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    private static Component showChangesLabel(boolean enabled) {
        return Component.translatable(enabled
                ? "gui.temporalindustries.time_machine.hide_changes"
                : "gui.temporalindustries.time_machine.show_changes");
    }

    private long getCurrentGameTime() {
        if (minecraft == null || minecraft.level == null) {
            return 0L;
        }
        return minecraft.level.getGameTime();
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, INVENTORY_TEXTURE);
        guiGraphics.blit(INVENTORY_TEXTURE, leftPos, topPos, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        renderTimelineGraph(guiGraphics);

        int energyStored = menu.getEnergyStored();
        int energyCapacity = menu.getEnergyCapacity();

        int barX = leftPos + ENERGY_BAR_X_OFFSET;
        int barY = topPos + ENERGY_BAR_Y_OFFSET;

        guiGraphics.fill(barX, barY, barX + ENERGY_BAR_WIDTH, barY + ENERGY_BAR_HEIGHT, 0xFF000000);
        if (energyCapacity > 0 && energyStored > 0) {
            int filled = Math.max(1, Math.round((energyStored / (float) energyCapacity) * (ENERGY_BAR_WIDTH - 2)));
            guiGraphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + ENERGY_BAR_HEIGHT - 1, 0xFF4DD0E1);
        }
    }

    private boolean isMouseOverEnergyBar(int mouseX, int mouseY) {
        int barX = leftPos + ENERGY_BAR_X_OFFSET;
        int barY = topPos + ENERGY_BAR_Y_OFFSET;
        return mouseX >= barX && mouseX <= barX + ENERGY_BAR_WIDTH && mouseY >= barY && mouseY <= barY + ENERGY_BAR_HEIGHT;
    }

    private void renderTimelineGraph(GuiGraphics guiGraphics) {
        renderedCommits.clear();

        int graphX = leftPos + GRAPH_X_OFFSET;
        int graphY = topPos + GRAPH_Y_OFFSET;
        int graphWidth = GRAPH_WIDTH;
        int graphHeight = GRAPH_HEIGHT;

        guiGraphics.fill(graphX, graphY, graphX + graphWidth, graphY + graphHeight, 0xFF000000);

        List<TemporalCommit> commits = TimelineProjectionManager.getCommits();
        if (commits.isEmpty()) return;

        Map<Long, Long> localParentById = TimelineProjectionManager.getLocalParents();

        if (commits != cachedLayoutCommits) {
            // Assign each commit a lane (row): the first child continues its parent's lane,
            // additional children fork into new lanes. localParentById is this chunk's own fork
            // history, distinct from a commit's dimension-wide getParentId().
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
        int headBlinkAlpha = Math.round((HEAD_BLINK_ALPHA_MIN + (HEAD_BLINK_ALPHA_MAX - HEAD_BLINK_ALPHA_MIN)
                * (0.5F + 0.5F * (float) Math.sin(2.0D * Math.PI * headBlinkPhase))) * 255.0F);
        int headHighlightColor = (headBlinkAlpha << 24) | HEAD_HIGHLIGHT_RGB;

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

                if (isBranch) {
                    // Branch points render as a hollow ring so a fork is visually distinct from a plain commit.
                    int outer = r + 1;
                    int inner = Math.max(1, r - 1);
                    guiGraphics.fill(pointX - outer, pointY - outer, pointX + outer + 1, pointY + outer + 1, color);
                    guiGraphics.fill(pointX - inner, pointY - inner, pointX + inner + 1, pointY + inner + 1, 0xFF000000);
                } else {
                    guiGraphics.fill(pointX - r, pointY - r, pointX + r + 1, pointY + r + 1, color);
                }

                if (commit.getId() == headCommitId) {
                    drawHeadHighlight(guiGraphics, pointX, pointY, r, headHighlightColor);
                }

                renderedCommits.add(new RenderedCommit(commit, pointX, pointY, r));
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
            if (rc.commit.getType() == TemporalCommit.Type.BRANCH) {
                int outer = rc.hitRadius + 1;
                int inner = Math.max(1, rc.hitRadius - 1);
                guiGraphics.fill(rc.x - outer, rc.y - outer, rc.x + outer + 1, rc.y + outer + 1, 0xFFFFFFFF);
                guiGraphics.fill(rc.x - inner, rc.y - inner, rc.x + inner + 1, rc.y + inner + 1, 0xFF000000);
            } else {
                guiGraphics.fill(rc.x - rc.hitRadius, rc.y - rc.hitRadius, rc.x + rc.hitRadius + 1, rc.y + rc.hitRadius + 1, 0xFFFFFFFF);
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
            drawScaledLabel(guiGraphics, Component.literal(label), pointX, pointY + r + 3, 0xFFBFBFBF, 1.0F);
        }

        guiGraphics.disableScissor();
    }

    private int nodeRadius() {
        return Math.max(1, (int) Math.round(2 * zoom));
    }

    /** Draws a pulsing hollow square halo just outside a node, marking the chunk's live head. */
    private static void drawHeadHighlight(GuiGraphics guiGraphics, int pointX, int pointY, int r, int color) {
        int outer = r + 3;
        int inner = outer - 1;
        guiGraphics.fill(pointX - outer, pointY - outer, pointX + outer + 1, pointY - inner, color);
        guiGraphics.fill(pointX - outer, pointY + inner, pointX + outer + 1, pointY + outer + 1, color);
        guiGraphics.fill(pointX - outer, pointY - outer, pointX - inner, pointY + outer + 1, color);
        guiGraphics.fill(pointX + inner, pointY - outer, pointX + outer + 1, pointY + outer + 1, color);
    }

    /** Draws label horizontally centered under (centerX, topY) — topY is where the text starts. */
    private void drawScaledLabel(GuiGraphics guiGraphics, Component label, int centerX, int topY, int color, float extraScale) {
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

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        // Skip super.render() to avoid rendering inventory slots
        renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderLabels(guiGraphics, mouseX, mouseY);
        for (var widget : renderables) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        RenderedCommit hovered = getCommitAt(mouseX, mouseY);
        if (hovered != null) {
            TemporalCommit commit = hovered.commit;
            int timelineIndex = timelineIndexById.getOrDefault(commit.getId(), 0);
            String timelineName = GREEK_NAMES[timelineIndex % GREEK_NAMES.length];
            List<Component> tooltip = new ArrayList<>(List.of(
                    Component.literal(timelineName + " Timeline"),
                    Component.literal("Commit: " + commit.getShortHash()),
                    Component.literal("Recorded: " + formatGameDayTime(commit.getGameTime()))
            ));
            if (commit.getType() == TemporalCommit.Type.BRANCH) {
                tooltip.add(Component.literal("Branch point"));
            } else {
                tooltip.add(Component.literal("Changes: " + commit.getTotalChangeCount()));
            }
            OptionalLong jumpCost = TimelineProjectionManager.getJumpCost(commit.getId());
            if (jumpCost.isPresent()) {
                tooltip.add(Component.literal("Jump cost: " + jumpCost.getAsLong() + " FE"));
            }
            List<FormattedCharSequence> lines = tooltip.stream().map(Component::getVisualOrderText).toList();
            guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
        } else if (isMouseOverEnergyBar(mouseX, mouseY)) {
            Component tooltip = Component.literal(menu.getEnergyStored() + " / " + menu.getEnergyCapacity() + " FE");
            guiGraphics.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private boolean isInGraphArea(double mouseX, double mouseY) {
        int graphX = leftPos + GRAPH_X_OFFSET;
        int graphY = topPos + GRAPH_Y_OFFSET;
        return mouseX >= graphX && mouseX <= graphX + GRAPH_WIDTH && mouseY >= graphY && mouseY <= graphY + GRAPH_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInGraphArea(mouseX, mouseY)) {
            RenderedCommit clicked = getCommitAt((int) mouseX, (int) mouseY);
            if (clicked != null) {
                selectCommit(clicked.commit);
            } else {
                draggingView = true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingView) {
            viewOffsetX += dragX;
            viewOffsetY += dragY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingView) {
            draggingView = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isInGraphArea(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        double delta = scrollY;
        double oldZoom = zoom;
        double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * Math.pow(1.1D, delta)));
        if (newZoom == oldZoom) {
            return true;
        }

        // Keep whatever graph point is under the cursor fixed on screen while zooming,
        // instead of zooming around the graph's origin.
        double originX = leftPos + GRAPH_X_OFFSET + 12;
        double originY = topPos + GRAPH_Y_OFFSET;
        double unitX = (mouseX - originX - viewOffsetX) / (COLUMN_SPACING * oldZoom);
        double unitY = (mouseY - originY - viewOffsetY) / (ROW_SPACING * oldZoom);

        zoom = newZoom;
        viewOffsetX = mouseX - originX - unitX * COLUMN_SPACING * zoom;
        viewOffsetY = mouseY - originY - unitY * ROW_SPACING * zoom;
        return true;
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Skip super.renderLabels() to avoid rendering inventory slot labels.
        // Our render() override skips AbstractContainerScreen's leftPos/topPos translate,
        // so coordinates here must be absolute (unlike vanilla renderLabels overrides).
        guiGraphics.drawString(font, Component.translatable("block.temporalindustries.time_machine"), leftPos + 8, topPos + 8, 0xFFFFFF, false);

        long now = TimelineProjectionManager.getCurrentGameTime();
        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.time_machine.preview_current", formatGameDayTime(now)), leftPos + 8, topPos + 200, 0xFFFFFF, false);

        if (TimelineProjectionManager.hasSelection()) {
            long selected = TimelineProjectionManager.getSelectedGameTime();
            long diff = selected - now;
            String direction = diff <= 0L
                    ? "gui.temporalindustries.time_machine.preview_past"
                    : "gui.temporalindustries.time_machine.preview_future";
            guiGraphics.drawString(font, Component.translatable(direction, formatSincePlaced(Math.abs(diff))), leftPos + 8, topPos + 210, 0xFFFFFF, false);
        }
    }

    private RenderedCommit getCommitAt(int mouseX, int mouseY) {
        for (RenderedCommit rc : renderedCommits) {
            int tolerance = rc.hitRadius + 2;
            if (Math.abs(mouseX - rc.x) <= tolerance && Math.abs(mouseY - rc.y) <= tolerance) return rc;
        }
        return null;
    }

    private static final class RenderedCommit {
        private final TemporalCommit commit;
        private final int x;
        private final int y;
        private final int hitRadius;

        private RenderedCommit(TemporalCommit commit, int x, int y, int hitRadius) {
            this.commit = commit;
            this.x = x;
            this.y = y;
            this.hitRadius = hitRadius;
        }
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

    private static String formatSincePlaced(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
