package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.network.ChronoMarkerDiffSyncPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Lists the block changes between a player's latest two Portable ChronoMarker marks. Not
 * menu-backed — the diff is a one-shot server push (see ChronoMarkerDiffSyncPacket), so there's
 * nothing to keep synced with a server-side container once the screen is open.
 */
public class PortableChronoMarkerScreen extends Screen {
    private static final int ROW_HEIGHT = 12;
    private static final int LIST_TOP = 46;
    private static final int LIST_MARGIN = 20;

    private final long fromGameTime;
    private final long toGameTime;
    private final List<ChronoMarkerDiffSyncPacket.Entry> entries;
    private double scrollOffset = 0;

    public PortableChronoMarkerScreen(long fromGameTime, long toGameTime, List<ChronoMarkerDiffSyncPacket.Entry> entries) {
        super(Component.translatable("gui.temporalindustries.portable_chrono_marker.title"));
        this.fromGameTime = fromGameTime;
        this.toGameTime = toGameTime;
        this.entries = entries;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> onClose())
                .pos(width / 2 - 50, height - 28)
                .size(100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        guiGraphics.drawCenteredString(font, Component.literal(formatGameDayTime(fromGameTime) + " -> " + formatGameDayTime(toGameTime)), width / 2, 22, 0x808080);

        Component subtitle = entries.isEmpty()
                ? Component.translatable("gui.temporalindustries.portable_chrono_marker.no_changes")
                : Component.translatable("gui.temporalindustries.portable_chrono_marker.change_count", entries.size());
        guiGraphics.drawCenteredString(font, subtitle, width / 2, 33, 0xA0A0A0);

        int listBottom = height - 36;
        guiGraphics.enableScissor(LIST_MARGIN, LIST_TOP, width - LIST_MARGIN, listBottom);

        int y = LIST_TOP - (int) scrollOffset;
        for (ChronoMarkerDiffSyncPacket.Entry entry : entries) {
            if (y + ROW_HEIGHT >= LIST_TOP && y <= listBottom) {
                Component row = Component.literal(
                        entry.oldState().getBlock().getName().getString() + " → " + entry.newState().getBlock().getName().getString()
                                + "  (" + entry.pos().getX() + ", " + entry.pos().getY() + ", " + entry.pos().getZ() + ")");
                guiGraphics.drawString(font, row, LIST_MARGIN + 4, y, 0xE0E0E0, false);
            }
            y += ROW_HEIGHT;
        }

        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double maxScroll = Math.max(0, entries.size() * ROW_HEIGHT - (height - 36 - LIST_TOP));
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - scrollY * ROW_HEIGHT));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Formats an absolute game time as "Day {day} | {HH:MM}" — mirrors TimelineGraphWidget's
     * identical helper so the two GUIs read the same time the same way. */
    private static String formatGameDayTime(long gameTime) {
        long day = Math.floorDiv(gameTime, 24000L) + 1L;
        long dayTicks = Math.floorMod(gameTime, 24000L);
        long hour = ((dayTicks / 1000L) + 6L) % 24L;
        long minute = (dayTicks % 1000L) * 60L / 1000L;
        return String.format("Day %d | %02d:%02d", day, hour, minute);
    }
}
