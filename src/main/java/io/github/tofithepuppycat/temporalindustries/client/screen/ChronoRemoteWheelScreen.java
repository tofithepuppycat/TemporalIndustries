package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Selection wheel of the Chrono Remote Control: opens while right-click is held, the option the
 * cursor points at runs when the button is released (or on left-click). The same screen shows the
 * main wheel, the settings wheel and the jump confirmation.
 */
public class ChronoRemoteWheelScreen extends Screen {
    private static final int WHEEL_RADIUS = 78;
    private static final int DEAD_ZONE = 18;
    private static final int TILE_WIDTH = 100;
    private static final int TILE_HEIGHT = 24;
    private static final int HINT_GAP = 18;

    private static final int TILE_COLOR = 0xB0202028;
    private static final int TILE_HOVER_COLOR = 0xE0546E9E;
    private static final int TILE_ARMED_COLOR = 0xE0A03838;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFB8B8C8;
    private static final int ON_COLOR = 0xFF66FF8C;
    private static final int OFF_COLOR = 0xFFFF7070;

    /** One tile of the wheel. */
    private static final class Option {
        private final Component label;
        private final Component hint;
        private final Runnable action;
        private boolean keepsWheelOpen;
        private boolean needsConfirm;
        @Nullable private BooleanSupplier state;

        Option(String labelKey, @Nullable String hintKey, Runnable action) {
            this.label = Component.translatable(labelKey);
            this.hint = hintKey == null ? Component.empty() : Component.translatable(hintKey);
            this.action = action;
        }

        /** A setting shown with its on/off state that stays open so several can be flipped in a row. */
        Option toggle(BooleanSupplier state) {
            this.state = state;
            this.keepsWheelOpen = true;
            return this;
        }

        /** Runs only on a second click, for actions that destroy something. */
        Option confirm() {
            this.needsConfirm = true;
            return this;
        }
    }

    private final List<Option> options;
    @Nullable private Option armed;

    private ChronoRemoteWheelScreen(List<Option> options) {
        super(Component.translatable("gui.temporalindustries.chrono_remote.title"));
        this.options = options;
    }

    /** Opens the wheel that fits what the remote is doing: the jump confirmation while previewing, else the main wheel. */
    public static void open() {
        Minecraft.getInstance().setScreen(ChronoRemoteClientState.isPreviewing() ? confirmWheel() : mainWheel());
    }

    private static ChronoRemoteWheelScreen mainWheel() {
        return new ChronoRemoteWheelScreen(List.of(
                new Option("gui.temporalindustries.chrono_remote.commit",
                        "gui.temporalindustries.chrono_remote.hint.commit",
                        () -> send(ChronoRemoteActionPacket.Action.COMMIT)),
                new Option(ChronoRemoteClientState.isSelecting()
                                ? "gui.temporalindustries.chrono_remote.stop_selecting"
                                : "gui.temporalindustries.chrono_remote.select_chunks",
                        "gui.temporalindustries.chrono_remote.hint.select_chunks",
                        ChronoRemoteWheelScreen::toggleSelecting),
                new Option("gui.temporalindustries.chrono_remote.settings",
                        "gui.temporalindustries.chrono_remote.hint.settings",
                        ChronoRemoteWheelScreen::openSettings),
                new Option("gui.temporalindustries.chrono_remote.jump",
                        "gui.temporalindustries.chrono_remote.hint.jump",
                        ChronoRemoteWheelScreen::openTimeline)));
    }

    private static ChronoRemoteWheelScreen settingsWheel() {
        send(ChronoRemoteActionPacket.Action.SYNC);
        String prefix = "gui.temporalindustries.chrono_remote.setting.";
        String hints = "gui.temporalindustries.chrono_remote.hint.";
        return new ChronoRemoteWheelScreen(List.of(
                new Option(prefix + "ghost_preview", hints + "ghost_preview", TimelineProjectionManager::toggleGhostBlockMode)
                        .toggle(TimelineProjectionManager::isGhostBlockModeEnabled),
                new Option(prefix + "save_entities", hints + "save_entities",
                        () -> send(ChronoRemoteActionPacket.Action.TOGGLE_SAVE_ENTITIES))
                        .toggle(ChronoRemoteClientState::isSaveEntities),
                new Option(prefix + "charge_containers", hints + "charge_containers",
                        () -> send(ChronoRemoteActionPacket.Action.TOGGLE_CHARGE_CONTAINERS))
                        .toggle(ChronoRemoteClientState::isChargeContainers),
                new Option("gui.temporalindustries.chrono_remote.back", null,
                        () -> Minecraft.getInstance().setScreen(mainWheel())),
                new Option(prefix + "delete_repository", hints + "delete_repository",
                        () -> send(ChronoRemoteActionPacket.Action.DELETE_HISTORY)).confirm(),
                new Option(prefix + "change_repository", hints + "change_repository",
                        () -> send(ChronoRemoteActionPacket.Action.UNBIND_REPOSITORY)).confirm()));
    }

    private static ChronoRemoteWheelScreen confirmWheel() {
        return new ChronoRemoteWheelScreen(List.of(
                new Option("gui.temporalindustries.chrono_remote.cancel", null, ChronoRemoteClientState::endSession),
                new Option("gui.temporalindustries.chrono_remote.jump", null, () -> {
                    send(ChronoRemoteActionPacket.Action.JUMP, ChronoRemoteClientState.getPreviewCommitId());
                    ChronoRemoteClientState.endSession();
                })));
    }

    private static void send(ChronoRemoteActionPacket.Action action) {
        PacketDistributor.sendToServer(new ChronoRemoteActionPacket(action));
    }

    private static void send(ChronoRemoteActionPacket.Action action, long argument) {
        PacketDistributor.sendToServer(new ChronoRemoteActionPacket(action, argument));
    }

    private static void toggleSelecting() {
        if (ChronoRemoteClientState.isSelecting()) {
            ChronoRemoteClientState.endSession();
            return;
        }
        ChronoRemoteClientState.startSelecting();
        send(ChronoRemoteActionPacket.Action.SYNC);
    }

    private static void openSettings() {
        Minecraft.getInstance().setScreen(settingsWheel());
    }

    private static void openTimeline() {
        Minecraft minecraft = Minecraft.getInstance();
        GlobalPos linked = minecraft.player == null ? null : ChronoRemoteControlItem.findHeldLink(minecraft.player);
        if (linked == null) return;
        if (minecraft.level == null || !linked.dimension().equals(minecraft.level.dimension())) {
            minecraft.player.displayClientMessage(
                    Component.translatable("gui.temporalindustries.chrono_remote.msg.wrong_dimension"), true);
            return;
        }

        ChronoRemoteClientState.endSession();
        minecraft.setScreen(new ChronoRemoteTimelineScreen(linked.pos()));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int centerX() {
        return width / 2;
    }

    private int centerY() {
        return height / 2;
    }

    /** Center of option index's tile; option 0 sits at the top, the rest clockwise. */
    private double tileAngle(int index) {
        return -Math.PI / 2.0D + 2.0D * Math.PI * index / options.size();
    }

    @Nullable
    private Option hovered(double mouseX, double mouseY) {
        double dx = mouseX - centerX();
        double dy = mouseY - centerY();
        if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) return null;

        double step = 2.0D * Math.PI / options.size();
        double angle = Math.atan2(dy, dx) + Math.PI / 2.0D + step / 2.0D;
        angle = ((angle % (2.0D * Math.PI)) + 2.0D * Math.PI) % (2.0D * Math.PI);
        return options.get((int) (angle / step) % options.size());
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        Option hovered = hovered(mouseX, mouseY);
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            int x = centerX() + (int) Math.round(Math.cos(tileAngle(i)) * WHEEL_RADIUS);
            int y = centerY() + (int) Math.round(Math.sin(tileAngle(i)) * WHEEL_RADIUS);
            int left = x - TILE_WIDTH / 2;
            int top = y - TILE_HEIGHT / 2;

            int color = option == armed ? TILE_ARMED_COLOR : (option == hovered ? TILE_HOVER_COLOR : TILE_COLOR);
            guiGraphics.fill(left, top, left + TILE_WIDTH, top + TILE_HEIGHT, color);

            Component detail = null;
            int detailColor = TEXT_COLOR;
            if (option == armed) {
                detail = Component.translatable("gui.temporalindustries.chrono_remote.click_to_confirm");
            } else if (option.state != null) {
                boolean on = option.state.getAsBoolean();
                detail = Component.translatable(on ? "gui.temporalindustries.chrono_remote.on" : "gui.temporalindustries.chrono_remote.off");
                detailColor = on ? ON_COLOR : OFF_COLOR;
            }

            if (detail == null) {
                guiGraphics.drawCenteredString(font, option.label, x, y - font.lineHeight / 2, TEXT_COLOR);
            } else {
                guiGraphics.drawCenteredString(font, option.label, x, top + 3, TEXT_COLOR);
                guiGraphics.drawCenteredString(font, detail, x, top + 3 + font.lineHeight, detailColor);
            }
        }

        if (hovered != null && hovered.hint != Component.empty()) {
            guiGraphics.drawCenteredString(font, hovered.hint, centerX(),
                    centerY() + WHEEL_RADIUS + TILE_HEIGHT / 2 + HINT_GAP, HINT_COLOR);
        }
        guiGraphics.fill(centerX() - 2, centerY() - 2, centerX() + 2, centerY() + 2, TEXT_COLOR);
    }

    private void choose(double mouseX, double mouseY) {
        Option option = hovered(mouseX, mouseY);
        if (option == null) {
            onClose();
            return;
        }
        if (option.needsConfirm && option != armed) {
            armed = option;
            return;
        }

        armed = null;
        Screen before = minecraft == null ? null : minecraft.screen;
        option.action.run();
        // An option that opened another screen already replaced this one.
        if (!option.keepsWheelOpen && minecraft != null && minecraft.screen == before) {
            onClose();
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            choose(mouseX, mouseY);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            choose(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
