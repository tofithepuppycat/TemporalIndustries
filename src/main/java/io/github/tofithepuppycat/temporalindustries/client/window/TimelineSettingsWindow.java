package io.github.tofithepuppycat.temporalindustries.client.window;

import java.util.function.Supplier;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereSetChargeContainersPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereSetEntityKindsPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereSetRollTimeOfDayPacket;
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
    private static final ResourceLocation ICON_ON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_on.png");
    private static final ResourceLocation ICON_OFF_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_off.png");

    private static final int DELETE_BUTTON_WIDTH = 140;
    private static final int DELETE_BUTTON_HEIGHT = 20;
    private static final int CONFIRM_BUTTON_WIDTH = 66;
    private static final int TOGGLE_SIZE = IconButtonRenderer.SIZE;

    // Rows share one left/right inset; a "display" section and a "danger" section are split by
    // dividers.
    private static final int INSET = 12;
    private static final int LINE_HEIGHT = 9;
    private static final int TITLE_DIVIDER_Y_OFFSET = 16;
    private static final int GHOST_TOGGLE_ROW_Y_OFFSET = 24;
    private static final int SAVE_ENTITIES_ROW_Y_OFFSET = GHOST_TOGGLE_ROW_Y_OFFSET + TOGGLE_SIZE + 6;
    // The item, mob and other toggles sit under "Save and Load Entities" while it is on, pushing the rows
    // beneath down by their combined height.
    private static final int SUB_ROW_STRIDE = TOGGLE_SIZE + 3;
    private static final int SUB_ROW_INDENT = 10;
    private static final int SAVE_ITEMS_ROW_Y_OFFSET = SAVE_ENTITIES_ROW_Y_OFFSET + SUB_ROW_STRIDE;
    private static final int SAVE_MOBS_ROW_Y_OFFSET = SAVE_ITEMS_ROW_Y_OFFSET + SUB_ROW_STRIDE;
    private static final int SAVE_OTHERS_ROW_Y_OFFSET = SAVE_MOBS_ROW_Y_OFFSET + SUB_ROW_STRIDE;
    private static final int CHARGE_CONTAINERS_ROW_Y_OFFSET = SAVE_ENTITIES_ROW_Y_OFFSET + TOGGLE_SIZE + 6;
    private static final int CHARGE_CONTAINERS_HINT_Y_OFFSET = CHARGE_CONTAINERS_ROW_Y_OFFSET + TOGGLE_SIZE + 3;
    private static final int CHARGE_CONTAINERS_HINT_HEIGHT = 2 * LINE_HEIGHT;
    private static final int ROLL_TIME_ROW_Y_OFFSET = CHARGE_CONTAINERS_HINT_Y_OFFSET + CHARGE_CONTAINERS_HINT_HEIGHT + 4;
    private static final int SAVE_ENTITIES_DIVIDER_Y_OFFSET = ROLL_TIME_ROW_Y_OFFSET + TOGGLE_SIZE + 6;
    private static final int CHANGE_REPOSITORY_BUTTON_Y_OFFSET = SAVE_ENTITIES_DIVIDER_Y_OFFSET + 4;
    private static final int DANGER_DIVIDER_Y_OFFSET = CHANGE_REPOSITORY_BUTTON_Y_OFFSET + DELETE_BUTTON_HEIGHT + 6;
    private static final int DELETE_HISTORY_HINT_Y_OFFSET = DANGER_DIVIDER_Y_OFFSET + 4;
    private static final int DELETE_HISTORY_BUTTON_Y_OFFSET = DELETE_HISTORY_HINT_Y_OFFSET + 3 * LINE_HEIGHT + 5;
    private static final int CONTENT_HEIGHT = DELETE_HISTORY_BUTTON_Y_OFFSET + DELETE_BUTTON_HEIGHT + 6;
    /** The rows scroll beneath the title, within this band of the window. */
    private static final int VIEWPORT_TOP = TITLE_DIVIDER_Y_OFFSET + 2;
    private static final int VIEWPORT_HEIGHT = MEDIUM_PANEL.height() - 6 - VIEWPORT_TOP;
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
        super(Component.translatable("gui.temporalindustries.timeline_machine.settings_title"), MEDIUM_PANEL);
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

        scroll.update(contentHeight(), VIEWPORT_HEIGHT);
        guiGraphics.enableScissor(x, y + VIEWPORT_TOP, x + panelWidth, y + VIEWPORT_TOP + VIEWPORT_HEIGHT);
        renderRows(guiGraphics, mouseX, mouseY);
        guiGraphics.disableScissor();
        scroll.renderBar(guiGraphics, x + panelWidth - INSET / 2 - 2, y + VIEWPORT_TOP, contentHeight(), VIEWPORT_HEIGHT);
    }

    private void renderRows(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int textX = x + INSET;
        int textWidth = panelWidth - 2 * INSET;
        int toggleX = toggleX();
        AbstractTimelineMachineBlockEntity be = machine.get();

        renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.timeline_machine.ghost_preview_label",
                GHOST_TOGGLE_ROW_Y_OFFSET, TimelineProjectionManager.isGhostBlockModeEnabled());
        renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.chronosphere.save_entities_label",
                SAVE_ENTITIES_ROW_Y_OFFSET, be.isSaveEntities());
        if (be.isSaveEntities()) {
            renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.chronosphere.save_items_label",
                    SAVE_ITEMS_ROW_Y_OFFSET, be.isSaveItemEntities(), SUB_ROW_INDENT);
            renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.chronosphere.save_mobs_label",
                    SAVE_MOBS_ROW_Y_OFFSET, be.isSaveMobEntities(), SUB_ROW_INDENT);
            renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.chronosphere.save_others_label",
                    SAVE_OTHERS_ROW_Y_OFFSET, be.isSaveOtherEntities(), SUB_ROW_INDENT);
        }
        renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.timeline_machine.charge_containers_label",
                lowerOffset(CHARGE_CONTAINERS_ROW_Y_OFFSET), be.isChargeContainers());
        drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.charge_containers_hint"),
                textX, rowY(lowerOffset(CHARGE_CONTAINERS_HINT_Y_OFFSET)), textWidth, COLOR_WARNING);

        renderToggleRow(guiGraphics, mouseX, mouseY, "gui.temporalindustries.timeline_machine.roll_time_label",
                lowerOffset(ROLL_TIME_ROW_Y_OFFSET), be.isRollTimeOfDay());

        drawDivider(guiGraphics, rowY(lowerOffset(SAVE_ENTITIES_DIVIDER_Y_OFFSET)), INSET);

        int changeX = centeredX(DELETE_BUTTON_WIDTH);
        int changeY = rowY(lowerOffset(CHANGE_REPOSITORY_BUTTON_Y_OFFSET));
        drawButton(guiGraphics, changeX, changeY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                Component.translatable("gui.temporalindustries.chronosphere.change_repository_button"), 0xFF3A5A8A,
                isOver(mouseX, mouseY, changeX, changeY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));

        drawDivider(guiGraphics, rowY(lowerOffset(DANGER_DIVIDER_Y_OFFSET)), INSET);

        int buttonY = rowY(lowerOffset(DELETE_HISTORY_BUTTON_Y_OFFSET));
        if (deleteHistoryConfirmPending) {
            drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.delete_history_confirm_prompt"),
                    textX, rowY(lowerOffset(DELETE_HISTORY_HINT_Y_OFFSET)), textWidth, 0xFFB03030);
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
                    textX, rowY(lowerOffset(DELETE_HISTORY_HINT_Y_OFFSET)), textWidth, TEXT_MUTED);
            int buttonX = centeredX(DELETE_BUTTON_WIDTH);
            drawButton(guiGraphics, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.timeline_machine.delete_history_button"), 0xFF8A3A3A,
                    isOver(mouseX, mouseY, buttonX, buttonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT));
        }
    }

    /** A label on the left with a two-layer icon toggle (backing plus true/false icon) on the right. */
    private void renderToggleRow(GuiGraphics guiGraphics, int mouseX, int mouseY, String labelKey, int rowOffset,
                                 boolean enabled) {
        renderToggleRow(guiGraphics, mouseX, mouseY, labelKey, rowOffset, enabled, 0);
    }

    private void renderToggleRow(GuiGraphics guiGraphics, int mouseX, int mouseY, String labelKey, int rowOffset,
                                 boolean enabled, int indent) {
        int toggleY = rowY(rowOffset);
        guiGraphics.drawString(font, Component.translatable(labelKey), x + INSET + indent,
                toggleY + (TOGGLE_SIZE - font.lineHeight) / 2, TEXT_PRIMARY, false);
        IconButtonRenderer.renderToggle(guiGraphics, enabled ? ICON_ON_TEXTURE : ICON_OFF_TEXTURE, enabled,
                isOver(mouseX, mouseY, toggleX(), toggleY, TOGGLE_SIZE, TOGGLE_SIZE), toggleX(), toggleY);
    }

    @Override
    protected boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isOver(mouseX, mouseY, x, y + VIEWPORT_TOP, panelWidth, VIEWPORT_HEIGHT)) {
            return true;
        }
        AbstractTimelineMachineBlockEntity be = machine.get();

        if (isOverToggle(mouseX, mouseY, GHOST_TOGGLE_ROW_Y_OFFSET)) {
            TimelineProjectionManager.toggleGhostBlockMode();
        } else if (isOverToggle(mouseX, mouseY, SAVE_ENTITIES_ROW_Y_OFFSET)) {
            PacketDistributor.sendToServer(new ChronosphereSetSaveEntitiesPacket(machinePos, !be.isSaveEntities()));
        } else if (be.isSaveEntities() && isOverToggle(mouseX, mouseY, SAVE_ITEMS_ROW_Y_OFFSET)) {
            PacketDistributor.sendToServer(new ChronosphereSetEntityKindsPacket(machinePos, !be.isSaveItemEntities(), be.isSaveMobEntities(), be.isSaveOtherEntities()));
        } else if (be.isSaveEntities() && isOverToggle(mouseX, mouseY, SAVE_MOBS_ROW_Y_OFFSET)) {
            PacketDistributor.sendToServer(new ChronosphereSetEntityKindsPacket(machinePos, be.isSaveItemEntities(), !be.isSaveMobEntities(), be.isSaveOtherEntities()));
        } else if (be.isSaveEntities() && isOverToggle(mouseX, mouseY, SAVE_OTHERS_ROW_Y_OFFSET)) {
            PacketDistributor.sendToServer(new ChronosphereSetEntityKindsPacket(machinePos, be.isSaveItemEntities(), be.isSaveMobEntities(), !be.isSaveOtherEntities()));
        } else if (isOverToggle(mouseX, mouseY, lowerOffset(CHARGE_CONTAINERS_ROW_Y_OFFSET))) {
            PacketDistributor.sendToServer(new ChronosphereSetChargeContainersPacket(machinePos, !be.isChargeContainers()));
        } else if (isOverToggle(mouseX, mouseY, lowerOffset(ROLL_TIME_ROW_Y_OFFSET))) {
            PacketDistributor.sendToServer(new ChronosphereSetRollTimeOfDayPacket(machinePos, !be.isRollTimeOfDay()));
        } else if (isOver(mouseX, mouseY, centeredX(DELETE_BUTTON_WIDTH), rowY(lowerOffset(CHANGE_REPOSITORY_BUTTON_Y_OFFSET)),
                DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
            changeRepository();
        } else if (deleteHistoryConfirmPending) {
            int buttonY = rowY(lowerOffset(DELETE_HISTORY_BUTTON_Y_OFFSET));
            if (isOver(mouseX, mouseY, confirmButtonX(), buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                PacketDistributor.sendToServer(new TimelineMachineDeleteHistoryPacket(machinePos));
                close();
            } else if (isOver(mouseX, mouseY, cancelButtonX(), buttonY, CONFIRM_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
                deleteHistoryConfirmPending = false;
            }
        } else if (isOver(mouseX, mouseY, centeredX(DELETE_BUTTON_WIDTH), rowY(lowerOffset(DELETE_HISTORY_BUTTON_Y_OFFSET)),
                DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)) {
            deleteHistoryConfirmPending = true;
        }
        return true;
    }

    @Override
    protected boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        scroll.scroll(scrollY, contentHeight(), VIEWPORT_HEIGHT);
        return true;
    }

    /** Detaches the machine from its repository, which returns it to the name-entry panel. */
    private void changeRepository() {
        PacketDistributor.sendToServer(new ChronosphereUnbindRepositoryPacket(machinePos));
        TimelineProjectionManager.clearSelectedCommit();
        onRepositoryDetached.run();
        close();
    }

    /** A row below the entity toggles: its offset moved down by the item and mob rows while they show. */
    private int lowerOffset(int offset) {
        return offset + (machine.get().isSaveEntities() ? 3 * SUB_ROW_STRIDE : 0);
    }

    private int contentHeight() {
        return lowerOffset(CONTENT_HEIGHT);
    }

    private boolean isOverToggle(double mouseX, double mouseY, int rowOffset) {
        return isOver(mouseX, mouseY, toggleX(), rowY(rowOffset), TOGGLE_SIZE, TOGGLE_SIZE);
    }

    /** A row's screen y: its offset within the scrolled content, less the current scroll. */
    private int rowY(int contentOffset) {
        return y + contentOffset - scroll.offset();
    }

    private int toggleX() {
        return x + panelWidth - INSET - TOGGLE_SIZE;
    }

    private int centeredX(int width) {
        return x + (panelWidth - width) / 2;
    }

    private int confirmButtonX() {
        return x + (panelWidth - CONFIRM_BUTTON_WIDTH * 2 - 6) / 2;
    }

    private int cancelButtonX() {
        return confirmButtonX() + CONFIRM_BUTTON_WIDTH + 6;
    }
}
