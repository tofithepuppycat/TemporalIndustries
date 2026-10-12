package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.network.ChronoLensRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronoLensTravelPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Standalone (non-container) screen for the Chronoscope: browses the Overworld's dimension-wide
 * trunk timeline via {@link TimelineGraphWidget} and lets the player pick a moment to travel to.
 */
@SuppressWarnings("null")
public class ChronoLensScreen extends Screen {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/base.png");
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;
    private static final int CONTENT_MARGIN = 16;
    private static final int CONTENT_SIZE = TEXTURE_WIDTH - 2 * CONTENT_MARGIN;

    private static final int TEXT_PRIMARY = 0xFF2B2B2B;
    private static final int TEXT_MUTED = 0xFF7A7A7A;

    private static final int GRAPH_X_OFFSET = 8;
    private static final int GRAPH_Y_OFFSET = 18;
    private static final int GRAPH_WIDTH = CONTENT_SIZE - 2 * GRAPH_X_OFFSET;
    private static final int GRAPH_HEIGHT = 154;

    private static final int HINT_Y_OFFSET = 180;
    private static final int BUTTON_Y_OFFSET = 198;
    private static final int BUTTON_WIDTH = 96;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    private static final int SYNC_INTERVAL_TICKS = 40;

    private final TimelineGraphWidget graphWidget = new TimelineGraphWidget();

    private int leftPos;
    private int topPos;
    private int ticksSinceSync = 0;

    public ChronoLensScreen() {
        super(Component.translatable("gui.temporalindustries.chrono_lens.title"));
    }

    /**
     * Opens this screen client-side only — call from inside a {@code level.isClientSide} branch.
     */
    public static void open() {
        Minecraft.getInstance().setScreen(new ChronoLensScreen());
    }

    private int panelX() {
        return leftPos + CONTENT_MARGIN;
    }

    private int panelY() {
        return topPos + CONTENT_MARGIN;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - TEXTURE_WIDTH) / 2;
        topPos = (height - TEXTURE_HEIGHT) / 2;

        int groupX = panelX() + (CONTENT_SIZE - (BUTTON_WIDTH * 2 + BUTTON_GAP)) / 2;
        int buttonY = panelY() + BUTTON_Y_OFFSET;

        addRenderableWidget(Button.builder(Component.translatable("gui.temporalindustries.chrono_lens.cancel_button"), btn -> onClose())
                .pos(groupX, buttonY)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.temporalindustries.chrono_lens.travel_button"), btn -> travel())
                .pos(groupX + BUTTON_WIDTH + BUTTON_GAP, buttonY)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        TimelineProjectionManager.setActiveMachine(BlockPos.ZERO);
        graphWidget.init(BlockPos.ZERO);
        ticksSinceSync = 0;
        PacketDistributor.sendToServer(new ChronoLensRequestPacket());
    }

    @Override
    public void onClose() {
        graphWidget.onClose();
        TimelineProjectionManager.clearActiveMachine(BlockPos.ZERO);
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        ticksSinceSync++;
        if (ticksSinceSync >= SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            PacketDistributor.sendToServer(new ChronoLensRequestPacket());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(guiGraphics);
    }

    private void travel() {
        if (!TimelineProjectionManager.hasSelection()) {
            return;
        }
        long target = TimelineProjectionManager.getSelectedGameTime();
        PacketDistributor.sendToServer(new ChronoLensTravelPacket(target));
        TimelineProjectionManager.clearSelectedCommit();
        onClose();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        guiGraphics.drawString(font, title, panelX() + (CONTENT_SIZE - font.width(title)) / 2, panelY() + 8, TEXT_PRIMARY, false);

        graphWidget.render(guiGraphics, font, panelX() + GRAPH_X_OFFSET, panelY() + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT);

        Component hint = Component.translatable("gui.temporalindustries.chrono_lens.hint");
        guiGraphics.drawString(font, hint, panelX() + (CONTENT_SIZE - font.width(hint)) / 2, panelY() + HINT_Y_OFFSET, TEXT_MUTED, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        List<FormattedCharSequence> tooltip = graphWidget.getTooltipAt(mouseX, mouseY);
        if (!tooltip.isEmpty()) {
            guiGraphics.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && graphWidget.mouseClicked(mouseX, mouseY, panelX() + GRAPH_X_OFFSET, panelY() + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (graphWidget.mouseDragged(dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (graphWidget.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (graphWidget.mouseScrolled(mouseX, mouseY, scrollY, panelX() + GRAPH_X_OFFSET, panelY() + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
