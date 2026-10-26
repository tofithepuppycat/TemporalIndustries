package io.github.tofithepuppycat.temporalindustries.client.window;

import java.util.function.Supplier;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereSetChargeContainersPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereSetSaveEntitiesPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereUnbindRepositoryPacket;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineDeleteHistoryPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Settings shared by the Chronosphere and Chronovault: display options, the repository binding and
 * history deletion. The rows scroll when taller than the window.
 */
public final class TimelineSettingsWindow extends GuiWindow {
    private static final ResourceLocation ICON_TRUE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_true.png");
    private static final ResourceLocation ICON_FALSE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_false.png");

    private static final int DELETE_BUTTON_WIDTH = 140;
    private static final int DELETE_BUTTON_HEIGHT = 20;
    private static final int CONFIRM_BUTTON_WIDTH = 66;
    private static final int TOGGLE_SIZE = IconButtonRenderer.SIZE;

    // Rows share one left/right inset; a "display" section and a "danger" section are split by
    // dividers.
    private static final int INSET = 20;
    private static final int TITLE_DIVIDER_Y_OFFSET = 16;
    private static final int GHOST_TOGGLE_ROW_Y_OFFSET = 26;
    private static final int GHOST_TOGGLE_HINT_Y_OFFSET = GHOST_TOGGLE_ROW_Y_OFFSET + TOGGLE_SIZE + 4;
    private static final int SECTION_DIVIDER_Y_OFFSET = 86;
    private static final int SAVE_ENTITIES_ROW_Y_OFFSET = 92;
    private static final int CHARGE_CONTAINERS_ROW_Y_OFFSET = 118;
    private static final int CHARGE_CONTAINERS_HINT_Y_OFFSET = CHARGE_CONTAINERS_ROW_Y_OFFSET + TOGGLE_SIZE + 4;
    private static final int CHARGE_CONTAINERS_HINT_HEIGHT = 30;
    private static final int SAVE_ENTITIES_DIVIDER_Y_OFFSET = CHARGE_CONTAINERS_HINT_Y_OFFSET + CHARGE_CONTAINERS_HINT_HEIGHT + 2;
    private static final int CHANGE_REPOSITORY_BUTTON_Y_OFFSET = SAVE_ENTITIES_DIVIDER_Y_OFFSET + 4;
    private static final int DANGER_DIVIDER_Y_OFFSET = CHANGE_REPOSITORY_BUTTON_Y_OFFSET + 26;
    private static final int DELETE_HISTORY_HINT_Y_OFFSET = DANGER_DIVIDER_Y_OFFSET + 4;
    private static final int DELETE_HISTORY_BUTTON_Y_OFFSET = DANGER_DIVIDER_Y_OFFSET + 42;
    private static final int CONTENT_HEIGHT = DELETE_HISTORY_BUTTON_Y_OFFSET + DELETE_BUTTON_HEIGHT + 6;
    /** The rows scroll beneath the title, within this band of the window. */
    private static final int VIEWPORT_TOP = TITLE_DIVIDER_Y_OFFSET + 2;
    private static final int VIEWPORT_HEIGHT = SIZE - 6 - VIEWPORT_TOP;
    private static final int COLOR_WARNING = 0xFFB06A00;

    private final BlockPos machinePos;
    private final Supplier<AbstractTimelineMachineBlockEntity> machine;
    private final Runnable onRepositoryDetached;
    private final SettingsScroll scroll = new SettingsScroll();
    /** Whether the "are you sure" step of history deletion is showing. */
    private boolean deleteHistoryConfirmPending = false;

    /**
     * @param onRepositoryDetached run after the machine is told to drop its repository, so the
     *     owning screen can discard state tied to it
     */
    public TimelineSettingsWindow(BlockPos machinePos, Supplier<AbstractTimelineMachineBlockEntity> machine,
                                  Runnable onRepositoryDetached) {
        super(Component.translatable("gui.temporalindustries.timeline_machine.settings_title"));
        this.machinePos = machinePos;
        this.machine = machine;
        this.onRepositoryDetached = onRepositoryDetached;
    }

    @Override
    protected void onOpened() {
        scroll.reset();
        deleteHistoryConfirmPending = false;
    }

    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        drawDivider(guiGraphics, y + TITLE_DIVIDER_Y_OFFSET, INSET);

        scroll.update(CONTENT_HEIGHT, VIEWPORT_HEIGHT);
        guiGraphics.enableScissor(x, y + VIEWPORT_TOP, x + SIZE, y + VIEWPORT_TOP + VIEWPORT_HEIGHT);
        renderRows(guiGraphics, mouseX, mouseY);
        guiGraphics.disableScissor();
        scroll.renderBar(guiGraphics, x + SIZE - INSET / 2, y + VIEWPORT_TOP, CONTENT_HEIGHT, VIEWPORT_HEIGHT);
    }

    private void renderRows(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int textX = x + INSET;
        int textWidth = SIZE - 2 * INSET;
        int toggleX = toggleX();
        AbstractTimelineMachineBlockEntity be = machine.get();

        renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.timeline_machine.ghost_preview_label",
                GHOST_TOGGLE_ROW_Y_OFFSET, TimelineProjectionManager.isGhostBlockModeEnabled());
        drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.ghost_preview_hint",
                        TimelineProjectionManager.GHOST_BLOCK_RENDER_LIMIT),
                textX, rowY(GHOST_TOGGLE_HINT_Y_OFFSET), textWidth, TEXT_MUTED);

        drawDivider(guiGraphics, rowY(SECTION_DIVIDER_Y_OFFSET), INSET);

        renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.chronosphere.save_entities_label",
                SAVE_ENTITIES_ROW_Y_OFFSET, be.isSaveEntities());
        renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.timeline_machine.charge_containers_label",
                CHARGE_CONTAINERS_ROW_Y_OFFSET, be.isChargeContainers());
        drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.charge_containers_hint"),
                textX, rowY(CHARGE_CONTAINERS_HINT_Y_OFFSET), textWidth, COLOR_WARNING);

        drawDivider(guiGraphics, rowY(SAVE_ENTITIES_DIVIDER_Y_OFFSET), INSET);

        int changeX = centeredX(DELETE_BUTTON_WIDTH);
        int changeY = rowY(CHANGE_REPOSITORY_BUTTON_Y_OFFSET);
        drawButton(guiGraphics, changeX, changeY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                Component.translatable("gui.temporalindustries.chronosphere.change_repository_button"), 0xFF3A5A8A,
                isOver(mouseX, mouseY, changeX, changeY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));

        drawDivider(guiGraphics, rowY(DANGER_DIVIDER_Y_OFFSET), INSET);

        int buttonY = rowY(DELETE_HISTORY_BUTTON_Y_OFFSET);
        if (deleteHistoryConfirmPending) {
            drawCenteredNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.delete_history_confirm_prompt"),
                    x + SIZE / 2, buttonY - 14, 0xFFB03030);
            int confirmX = confirmButtonX();
            int cancelX = cancelButtonX();
            drawButton(guiGraphics, confirmX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.timeline_machine.delete_history_confirm_button"), 0xFF8A3A3A,
                    isOver(mouseX, mouseY, confirmX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
            drawButton(guiGraphics, cancelX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.timeline_machine.delete_history_cancel_button"), 0xFF3A3A3A,
                    isOver(mouseX, mouseY, cancelX, buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
        } else {
            drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.delete_history_hint"),
                    textX, rowY(DELETE_HISTORY_HINT_Y_OFFSET), textWidth, TEXT_MUTED);
            int buttonX = centeredX(DELETE_BUTTON_WIDTH);
            drawButton(guiGraphics, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.timeline_machine.delete_history_button"), 0xFF8A3A3A,
                    isOver(mouseX, mouseY, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
        }
    }

    /** A label on the left with a two-layer icon toggle (backing plus true/false icon) on the right. */
    private void renderToggleRow(GuiGraphics guiGraphics, int mouseX, int mouseY, String labelKey, int rowOffset,
                                 boolean enabled) {
        int toggleY = rowY(rowOffset);
        guiGraphics.drawString(font, Component.translatable(labelKey), x + INSET,
                toggleY + (TOGGLE_SIZE - font.lineHeight) / 2, TEXT_PRIMARY, false);
        IconButtonRenderer.renderToggle(guiGraphics, enabled ? ICON_TRUE_TEXTURE : ICON_FALSE_TEXTURE, enabled,
                isOver(mouseX, mouseY, toggleX(), toggleY, TOGGLE_SIZE, TOGGLE_SIZE), toggleX(), toggleY);
    }

    @Override
    protected boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isOver(mouseX, mouseY, x, y + VIEWPORT_TOP, SIZE, VIEWPORT_HEIGHT)) {
            return true;
        }
        AbstractTimelineMachineBlockEntity be = machine.get();

        if (isOverToggle(mouseX, mouseY, GHOST_TOGGLE_ROW_Y_OFFSET)) {
            TimelineProjectionManager.toggleGhostBlockMode();
        } else if (isOverToggle(mouseX, mouseY, SAVE_ENTITIES_ROW_Y_OFFSET)) {
            PacketDistributor.sendToServer(new ChronosphereSetSaveEntitiesPacket(machinePos, !be.isSaveEntities()));
        } else if (isOverToggle(mouseX, mouseY, CHARGE_CONTAINERS_ROW_Y_OFFSET)) {
            PacketDistributor.sendToServer(new ChronosphereSetChargeContainersPacket(machinePos, !be.isChargeContainers()));
        } else if (isOver(mouseX, mouseY, centeredX(DELETE_BUTTON_WIDTH), rowY(CHANGE_REPOSITORY_BUTTON_Y_OFFSET),
                DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
            changeRepository();
        } else if (deleteHistoryConfirmPending) {
            int buttonY = rowY(DELETE_HISTORY_BUTTON_Y_OFFSET);
            if (isOver(mouseX, mouseY, confirmButtonX(), buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                PacketDistributor.sendToServer(new TimelineMachineDeleteHistoryPacket(machinePos));
                close();
            } else if (isOver(mouseX, mouseY, cancelButtonX(), buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                deleteHistoryConfirmPending = false;
            }
        } else if (isOver(mouseX, mouseY, centeredX(DELETE_BUTTON_WIDTH), rowY(DELETE_HISTORY_BUTTON_Y_OFFSET),
                DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
            deleteHistoryConfirmPending = true;
        }
        return true;
    }

    @Override
    protected boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        scroll.scroll(scrollY, CONTENT_HEIGHT, VIEWPORT_HEIGHT);
        return true;
    }

    /** Detaches the machine from its repository, which returns it to the name-entry panel. */
    private void changeRepository() {
        PacketDistributor.sendToServer(new ChronosphereUnbindRepositoryPacket(machinePos));
        TimelineProjectionManager.clearSelectedCommit();
        onRepositoryDetached.run();
        close();
    }

    private boolean isOverToggle(double mouseX, double mouseY, int rowOffset) {
        return isOver(mouseX, mouseY, toggleX(), rowY(rowOffset), TOGGLE_SIZE, TOGGLE_SIZE);
    }

    /** A row's screen y: its offset within the scrolled content, less the current scroll. */
    private int rowY(int contentOffset) {
        return y + contentOffset - scroll.offset();
    }

    private int toggleX() {
        return x + SIZE - INSET - TOGGLE_SIZE;
    }

    private int centeredX(int width) {
        return x + (SIZE - width) / 2;
    }

    private int confirmButtonX() {
        return x + (SIZE - CONFIRM_BUTTON_WIDTH * 2 - 6) / 2;
    }

    private int cancelButtonX() {
        return confirmButtonX() + CONFIRM_BUTTON_WIDTH + 6;
    }
}
