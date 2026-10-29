package io.github.tofithepuppycat.temporalindustries.client.window;

import io.github.tofithepuppycat.temporalindustries.network.ChronosphereMapRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereToggleChunkPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** The Chronosphere's chunk claim map as a window over its menu, with a selection count and hint below it. */
public final class ChronosphereMapWindow extends GuiWindow {
    private static final int GRID_Y_OFFSET = 18;
    private static final int FOOTER_GAP = 4;
    private static final int FOOTER_LINE_SPACING = 10;

    private final ChronosphereMapView view;

    /** The map of the Chronosphere at machinePos, for use while its menu is open. */
    public ChronosphereMapWindow(BlockPos machinePos) {
        super(Component.translatable("gui.temporalindustries.chronosphere.map_title"));
        this.view = new ChronosphereMapView(machinePos,
                () -> PacketDistributor.sendToServer(new ChronosphereMapRequestPacket(machinePos)),
                toggles -> PacketDistributor.sendToServer(ChronosphereToggleChunkPacket.fromPending(machinePos, toggles)));
    }

    /** Drops the claims and releases not yet sent, for when the repository they apply to is gone. */
    public void discardPendingToggles() {
        view.discardPendingToggles();
    }

    @Override
    protected void onOpened() {
        view.open();
    }

    @Override
    protected void onClosed() {
        view.close();
    }

    @Override
    protected void tick() {
        view.tick();
    }

    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        view.layout(x, y + GRID_Y_OFFSET, SIZE);
        view.render(guiGraphics, mouseX, mouseY);

        // The block entity the menu holds is the client's stale copy, so ChronosphereClientState
        // (plus this visit's pending toggles) is the only client-accurate source.
        int footerY = y + GRID_Y_OFFSET + ChronosphereMapView.gridPixels() + FOOTER_GAP;
        drawCenteredNoShadow(guiGraphics, Component.literal(view.selectedCount() + "/" + ChronosphereMapView.totalClaimable() + " chunks selected"),
                x + SIZE / 2, footerY, TEXT_SECONDARY);
        drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.chronosphere.map_hint"),
                x + SIZE / 2, footerY + FOOTER_LINE_SPACING, TEXT_MUTED);
    }

    @Override
    protected boolean mouseClicked(double mouseX, double mouseY, int button) {
        view.mouseClicked(mouseX, mouseY, button);
        return true;
    }

    @Override
    protected boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        view.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        return true;
    }

    @Override
    protected boolean mouseReleased(double mouseX, double mouseY, int button) {
        view.mouseReleased(mouseX, mouseY, button);
        return true;
    }

    @Override
    protected boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        view.mouseScrolled(mouseX, mouseY, scrollY);
        return true;
    }
}
