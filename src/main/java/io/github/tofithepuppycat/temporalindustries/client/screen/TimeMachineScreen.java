package io.github.tofithepuppycat.temporalindustries.client.screen;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.mojang.blaze3d.systems.RenderSystem;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.menu.TimeMachineMenu;
import io.github.tofithepuppycat.temporalindustries.network.RollbackChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelinePreviewRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** GUI for the Time Machine block: renders its chunk's commit graph (via {@link TimelineGraphWidget})
 * and lets the player pick a point in time to jump to. */
@SuppressWarnings("null")
public class TimeMachineScreen extends AbstractContainerScreen<TimeMachineMenu> {
    private static final ResourceLocation INVENTORY_TEXTURE = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "textures/gui/timemachine.png");
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;

    private static final int GRAPH_X_OFFSET = 8;
    private static final int GRAPH_Y_OFFSET = 18;
    private static final int GRAPH_WIDTH = 240;
    private static final int GRAPH_HEIGHT = 178;

    private static final int ENERGY_BAR_X_OFFSET = 160;
    private static final int ENERGY_BAR_Y_OFFSET = 7;
    private static final int ENERGY_BAR_WIDTH = 88;
    private static final int ENERGY_BAR_HEIGHT = 8;

    private static final int SYNC_INTERVAL_TICKS = 20;

    private final TimelineGraphWidget graphWidget = new TimelineGraphWidget();

    private Button jumpButton;
    private Button showChangesButton;
    private int ticksSinceSync = 0;

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
        graphWidget.init(menu.getBlockPos());
        // Force a full response regardless of whatever TimelineProjectionManager still has
        // cached from a previously viewed machine (Long.MIN_VALUE can never equal a real head id).
        PacketDistributor.sendToServer(new TimelinePreviewRequestPacket(menu.getBlockPos(), Long.MIN_VALUE));
    }

    @Override
    public void onClose() {
        graphWidget.onClose();
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

        graphWidget.render(guiGraphics, font, leftPos + GRAPH_X_OFFSET, topPos + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT);

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

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        // Skip super.render() to avoid rendering inventory slots
        renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderLabels(guiGraphics, mouseX, mouseY);
        for (var widget : renderables) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        List<FormattedCharSequence> tooltip = graphWidget.getTooltipAt(mouseX, mouseY);
        if (!tooltip.isEmpty()) {
            guiGraphics.renderTooltip(font, tooltip, mouseX, mouseY);
        } else if (isMouseOverEnergyBar(mouseX, mouseY)) {
            Component tooltipComponent = Component.literal(menu.getEnergyStored() + " / " + menu.getEnergyCapacity() + " FE");
            guiGraphics.renderTooltip(font, tooltipComponent, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && graphWidget.mouseClicked(mouseX, mouseY, leftPos + GRAPH_X_OFFSET, topPos + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT)) {
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
        if (button == 0 && graphWidget.mouseReleased()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (graphWidget.mouseScrolled(mouseX, mouseY, scrollY, leftPos + GRAPH_X_OFFSET, topPos + GRAPH_Y_OFFSET, GRAPH_WIDTH, GRAPH_HEIGHT)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
