package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChunkThumbnailClientState;
import io.github.tofithepuppycat.temporalindustries.client.chunkmap.ChunkSelectionGrid;
import io.github.tofithepuppycat.temporalindustries.item.PortableChronoMarkerItem;
import io.github.tofithepuppycat.temporalindustries.network.ChronoMarkerMapRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronoMarkerMarkPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Standalone (non-container) screen for the Portable Chrono Marker's sneak-right-click area select:
 * the same {@link ChunkSelectionGrid} the Chronosphere's claim overlay uses, letting the player pick
 * exactly which chunks around them get captured on confirm, instead of the plain right-click's fixed
 * square radius. Opened directly via {@link #open}, not through a container menu, since there's no
 * block/BlockEntity backing this item's action.
 *
 * <p>Selection lives entirely client-side until "Mark" is pressed — unlike the Chronosphere's claim
 * map, there's no persistent server-side claim that other players/screens need to stay in sync with,
 * so nothing is sent to the server until the player confirms (see {@link ChronoMarkerMarkPacket}).
 */
@SuppressWarnings("null")
public class ChronoMarkerMapScreen extends Screen {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/base.png");
    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_HEIGHT = 256;

    private static final int TEXT_PRIMARY = 0xFF2B2B2B;
    private static final int TEXT_MUTED = 0xFF7A7A7A;

    private static final int COLOR_HOME = 0xFFFFD166;
    private static final int COLOR_SELECTED = 0xFF3D9BE0;
    private static final int COLOR_AVAILABLE = 0xFFAFAFAF;

    /** How often the map re-fetches terrain thumbnails while open, matching the Chronosphere claim
     * map's own refresh interval. */
    private static final int MAP_SYNC_INTERVAL_TICKS = 100;
    private static final int BUTTON_WIDTH = 76;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 3;

    private static final ChunkSelectionGrid GRID = new ChunkSelectionGrid(PortableChronoMarkerItem.MAP_RADIUS_CHUNKS);

    private final ChunkPos anchor;
    /** Chunk keys currently chosen for marking — always contains the anchor chunk, which can't be
     * deselected (mirrors the Chronosphere claim map's home chunk). */
    private final Set<Long> selected = new LinkedHashSet<>();

    private int leftPos;
    private int topPos;
    private int gridX;
    private int gridY;
    private int ticksSinceSync = 0;

    public ChronoMarkerMapScreen(ChunkPos anchor) {
        super(Component.translatable("gui.temporalindustries.chrono_marker.map_title"));
        this.anchor = anchor;
        this.selected.add(anchor.toLong());
    }

    /** Opens this screen client-side only — call from inside a {@code level.isClientSide} branch. */
    public static void open(ChunkPos anchor) {
        Minecraft.getInstance().setScreen(new ChronoMarkerMapScreen(anchor));
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - IMAGE_WIDTH) / 2;
        topPos = (height - IMAGE_HEIGHT) / 2;
        gridX = leftPos + (IMAGE_WIDTH - GRID.gridPixels()) / 2;
        gridY = topPos + 44;

        int buttonY = topPos + 224;
        int groupX = leftPos + (IMAGE_WIDTH - (BUTTON_WIDTH * 2 + BUTTON_GAP)) / 2;

        addRenderableWidget(Button.builder(Component.translatable("gui.temporalindustries.chrono_marker.cancel_button"), btn -> onClose())
                .pos(groupX, buttonY)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.temporalindustries.chrono_marker.mark_button"), btn -> confirmMark())
                .pos(groupX + BUTTON_WIDTH + BUTTON_GAP, buttonY)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        ticksSinceSync = 0;
        PacketDistributor.sendToServer(new ChronoMarkerMapRequestPacket(anchor.toLong()));
    }

    @Override
    public void onClose() {
        ChunkThumbnailClientState.clearAll();
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        ticksSinceSync++;
        if (ticksSinceSync >= MAP_SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            PacketDistributor.sendToServer(new ChronoMarkerMapRequestPacket(anchor.toLong()));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The base {@link Screen#renderBackground} applies the vanilla world-blur post-process behind
     * the GUI (see {@code Screen#renderBlurredBackground}); {@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}
     * (what ChronosphereScreen extends) overrides this to skip that entirely and just dim the world,
     * which is why its claim map never looks blurred. This screen isn't a container, so it has to
     * opt out the same way explicitly to match. */
    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(guiGraphics);
    }

    private void confirmMark() {
        PacketDistributor.sendToServer(new ChronoMarkerMarkPacket(new ArrayList<>(selected)));
        onClose();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        guiGraphics.drawString(font, title, leftPos + (IMAGE_WIDTH - font.width(title)) / 2, topPos + 16, TEXT_PRIMARY, false);

        GRID.render(guiGraphics, gridX, gridY, anchor, new ChunkSelectionGrid.CellPainter() {
            @Override
            public int tint(int dx, int dz, long chunkKey) {
                if (dx == 0 && dz == 0) return COLOR_HOME;
                return selected.contains(chunkKey) ? COLOR_SELECTED : COLOR_AVAILABLE;
            }

            @Override
            @Nullable
            public ResourceLocation texture(long chunkKey) {
                return ChunkThumbnailClientState.getTexture(chunkKey);
            }
        });

        int footerY = gridY + GRID.gridPixels() + 10;
        Component count = Component.translatable("gui.temporalindustries.chrono_marker.map_count", selected.size());
        guiGraphics.drawString(font, count, leftPos + (IMAGE_WIDTH - font.width(count)) / 2, footerY, TEXT_MUTED, false);

        Component hint = Component.translatable("gui.temporalindustries.chrono_marker.map_hint");
        guiGraphics.drawString(font, hint, leftPos + (IMAGE_WIDTH - font.width(hint)) / 2, footerY + 11, TEXT_MUTED, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            ChunkPos clicked = GRID.cellAt(mouseX, mouseY, gridX, gridY, anchor);
            if (clicked != null && !clicked.equals(anchor)) {
                long key = clicked.toLong();
                if (!selected.remove(key)) selected.add(key);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
