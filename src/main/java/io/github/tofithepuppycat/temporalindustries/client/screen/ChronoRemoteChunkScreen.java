package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.client.window.ChronosphereMapWindow;
import io.github.tofithepuppycat.temporalindustries.client.window.GuiWindow;
import io.github.tofithepuppycat.temporalindustries.client.window.WindowManager;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteActionPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteChunksPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * Chunk selection of the Chrono Remote Control: the linked Chronosphere's claim map, with a toggle
 * that outlines the selected chunks in the world once the screen is closed.
 */
public class ChronoRemoteChunkScreen extends Screen {
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTON_MARGIN = 4;

    private final WindowManager windows = new WindowManager();
    private final ChronosphereMapWindow mapWindow;

    public ChronoRemoteChunkScreen(BlockPos machinePos) {
        super(Component.translatable("gui.temporalindustries.chrono_remote.select_chunks"));
        mapWindow = new ChronosphereMapWindow(machinePos,
                () -> PacketDistributor.sendToServer(new ChronoRemoteActionPacket(ChronoRemoteActionPacket.Action.MAP)),
                toggles -> PacketDistributor.sendToServer(ChronoRemoteChunksPacket.fromPending(toggles)));
    }

    @Override
    protected void init() {
        // Leaves room under the window for the button row.
        int windowX = (width - GuiWindow.SIZE) / 2;
        int windowY = Math.max(0, (height - GuiWindow.SIZE - BUTTON_HEIGHT - BUTTON_MARGIN) / 2);
        if (!windows.isOpen(mapWindow)) {
            windows.open(mapWindow, windowX, windowY);
        }

        int rowY = windowY + GuiWindow.SIZE + BUTTON_MARGIN;
        int rowX = (width - 2 * BUTTON_WIDTH - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(previewLabel(), button -> {
            ChronoRemoteClientState.setChunkPreview(!ChronoRemoteClientState.isChunkPreview());
            button.setMessage(previewLabel());
        }).bounds(rowX, rowY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(rowX + BUTTON_WIDTH + BUTTON_GAP, rowY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private static Component previewLabel() {
        return Component.translatable("gui.temporalindustries.chrono_remote.preview_chunks",
                Component.translatable(ChronoRemoteClientState.isChunkPreview()
                        ? "gui.temporalindustries.chrono_remote.on"
                        : "gui.temporalindustries.chrono_remote.off"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        windows.tick();
        // The map's own close button leaves nothing worth keeping this screen for.
        if (!windows.isOpen(mapWindow)) {
            onClose();
        }
    }

    @Override
    public void removed() {
        // Closing sends the claims and releases the map still holds.
        windows.closeAll();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        boolean covered = windows.isOverWindow(mouseX, mouseY);
        super.render(guiGraphics, covered ? WindowManager.COVERED : mouseX, covered ? WindowManager.COVERED : mouseY, partialTick);
        windows.render(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return windows.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return windows.mouseDragged(mouseX, mouseY, button, dragX, dragY)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return windows.mouseReleased(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return windows.mouseScrolled(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
