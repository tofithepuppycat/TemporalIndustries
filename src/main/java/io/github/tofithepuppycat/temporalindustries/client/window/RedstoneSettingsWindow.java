package io.github.tofithepuppycat.temporalindustries.client.window;

import java.util.function.Supplier;

import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.RedstoneMode;
import io.github.tofithepuppycat.temporalindustries.network.TimelineMachineSetRedstoneModePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Chooses how a timeline machine reacts to redstone. */
public final class RedstoneSettingsWindow extends GuiWindow {
    private static final int INSET = 12;
    private static final int TITLE_DIVIDER_Y_OFFSET = 16;
    private static final int LABEL_Y_OFFSET = 24;
    private static final int FIRST_BUTTON_Y_OFFSET = LABEL_Y_OFFSET + 14;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int HINT_Y_OFFSET = FIRST_BUTTON_Y_OFFSET + RedstoneMode.values().length * (BUTTON_HEIGHT + BUTTON_GAP) + 4;

    private static final int COLOR_SELECTED = 0xFFB02A2A;
    private static final int COLOR_UNSELECTED = 0xFF555555;

    private final BlockPos machinePos;
    private final Supplier<AbstractTimelineMachineBlockEntity> machine;

    public RedstoneSettingsWindow(BlockPos machinePos, Supplier<AbstractTimelineMachineBlockEntity> machine) {
        super(Component.translatable("gui.temporalindustries.timeline_machine.redstone_title"), MEDIUM_PANEL);
        this.machinePos = machinePos;
        this.machine = machine;
    }

    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        drawDivider(guiGraphics, y + TITLE_DIVIDER_Y_OFFSET, INSET);

        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.timeline_machine.redstone_mode_label"),
                x + INSET, y + LABEL_Y_OFFSET, TEXT_PRIMARY, false);

        RedstoneMode current = machine.get().getRedstoneMode();
        for (RedstoneMode mode : RedstoneMode.values()) {
            int buttonY = buttonY(mode);
            drawButton(guiGraphics, buttonX(), buttonY, buttonWidth(), BUTTON_HEIGHT,
                    Component.translatable("gui.temporalindustries.timeline_machine.redstone_mode." + mode.key()),
                    mode == current ? COLOR_SELECTED : COLOR_UNSELECTED,
                    isOver(mouseX, mouseY, buttonX(), buttonY, buttonWidth(), BUTTON_HEIGHT));
        }

        drawWordWrapNoShadow(guiGraphics, Component.translatable("gui.temporalindustries.timeline_machine.redstone_hint"),
                x + INSET, y + HINT_Y_OFFSET, panelWidth - 2 * INSET, TEXT_MUTED);
    }

    @Override
    protected boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;
        for (RedstoneMode mode : RedstoneMode.values()) {
            if (isOver(mouseX, mouseY, buttonX(), buttonY(mode), buttonWidth(), BUTTON_HEIGHT)) {
                PacketDistributor.sendToServer(new TimelineMachineSetRedstoneModePacket(machinePos, mode));
                break;
            }
        }
        return true;
    }

    private int buttonX() {
        return x + INSET;
    }

    private int buttonWidth() {
        return panelWidth - 2 * INSET;
    }

    private int buttonY(RedstoneMode mode) {
        return y + FIRST_BUTTON_Y_OFFSET + mode.ordinal() * (BUTTON_HEIGHT + BUTTON_GAP);
    }
}
