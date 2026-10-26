package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;

/** Hint panel shown at the top of the screen while the Chrono Remote Control is in a mode. */
public final class ChronoRemoteOverlay implements LayeredDraw.Layer {
    public static final ChronoRemoteOverlay INSTANCE = new ChronoRemoteOverlay();

    private static final int TOP_MARGIN = 14;
    private static final int PADDING = 5;
    private static final int LINE_SPACING = 2;
    private static final int PANEL_COLOR = 0x99101018;
    private static final int TITLE_COLOR = 0xFF8FD8FF;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int INCLUDED_COLOR = 0xFF66FF8C;
    private static final int EXCLUDED_COLOR = 0xFFFFC04D;

    private ChronoRemoteOverlay() {}

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || !ChronoRemoteClientState.isActive()) {
            return;
        }

        Font font = minecraft.font;
        Component title;
        Component[] lines;
        int[] colors;
        if (ChronoRemoteClientState.isSelecting()) {
            long chunk = minecraft.player.chunkPosition().toLong();
            boolean included = ChronosphereClientState.isSelected(chunk);
            title = Component.translatable("gui.temporalindustries.chrono_remote.overlay.select_title");
            lines = new Component[] {
                    Component.translatable("gui.temporalindustries.chrono_remote.overlay.select_hint"),
                    Component.translatable(included
                                    ? "gui.temporalindustries.chrono_remote.overlay.chunk_included"
                                    : "gui.temporalindustries.chrono_remote.overlay.chunk_excluded",
                            minecraft.player.chunkPosition().x, minecraft.player.chunkPosition().z,
                            ChronosphereClientState.getSelectedCount())
            };
            colors = new int[] {TEXT_COLOR, included ? INCLUDED_COLOR : EXCLUDED_COLOR};
        } else {
            long commitId = ChronoRemoteClientState.getPreviewCommitId();
            String hash = "";
            for (CommitNode commit : TimelineProjectionManager.getCommits()) {
                if (commit.id() == commitId) {
                    hash = commit.shortHash();
                    break;
                }
            }
            title = Component.translatable("gui.temporalindustries.chrono_remote.overlay.preview_title", hash);
            lines = new Component[] {
                    Component.translatable("gui.temporalindustries.chrono_remote.overlay.preview_hint")
            };
            colors = new int[] {TEXT_COLOR};
        }

        int width = font.width(title);
        for (Component line : lines) width = Math.max(width, font.width(line));
        int height = font.lineHeight + lines.length * (font.lineHeight + LINE_SPACING);

        int left = (guiGraphics.guiWidth() - width) / 2 - PADDING;
        int top = TOP_MARGIN;
        guiGraphics.fill(left, top, left + width + 2 * PADDING, top + height + 2 * PADDING, PANEL_COLOR);

        int y = top + PADDING;
        guiGraphics.drawCenteredString(font, title, guiGraphics.guiWidth() / 2, y, TITLE_COLOR);
        for (int i = 0; i < lines.length; i++) {
            y += font.lineHeight + LINE_SPACING;
            guiGraphics.drawCenteredString(font, lines[i], guiGraphics.guiWidth() / 2, y, colors[i]);
        }
    }
}
