package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineProjectionManager;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteActionPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Selection wheel of the Chrono Remote Control: opens while right-click is held, the slice the
 * cursor points at runs when the button is released (or on left-click). The same screen shows the
 * main wheel, the settings wheel and the jump confirmation.
 */
public class ChronoRemoteWheelScreen extends Screen {
    private static final int INNER_RADIUS = 38;
    private static final int OUTER_RADIUS = 118;
    private static final int HUB_RADIUS = INNER_RADIUS - 6;
    private static final int HINT_GAP = 14;
    /** Angular gap left between neighbouring slices. */
    private static final double SLICE_GAP = Math.toRadians(1.6D);
    /** Largest angle one segment of a slice's outline may span. */
    private static final double SEGMENT_ANGLE = Math.toRadians(4.0D);
    private static final int LABEL_PADDING = 6;
    private static final int MAX_LABEL_LINES = 2;

    private static final int SLICE_COLOR = 0xB0202028;
    private static final int SLICE_HOVER_COLOR = 0xE0546E9E;
    private static final int SLICE_ARMED_COLOR = 0xE0A03838;
    private static final int HUB_COLOR = 0xC0101018;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFB8B8C8;
    private static final int ON_COLOR = 0xFF66FF8C;
    private static final int OFF_COLOR = 0xFFFF7070;

    /** One slice of the wheel. */
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
                new Option("gui.temporalindustries.chrono_remote.select_chunks",
                        "gui.temporalindustries.chrono_remote.hint.select_chunks",
                        ChronoRemoteWheelScreen::openChunkSelection),
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
                new Option(prefix + "change_repository", hints + "change_repository",
                        ChronoRemoteWheelScreen::openRepository)));
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

    private static void openChunkSelection() {
        Minecraft minecraft = Minecraft.getInstance();
        GlobalPos linked = minecraft.player == null ? null : ChronoRemoteControlItem.findHeldLink(minecraft.player);
        if (linked == null) return;
        if (minecraft.level == null || !linked.dimension().equals(minecraft.level.dimension())) {
            minecraft.player.displayClientMessage(
                    Component.translatable("gui.temporalindustries.chrono_remote.msg.wrong_dimension"), true);
            return;
        }

        // The reply closes the screen again if the machine can't have chunks selected.
        send(ChronoRemoteActionPacket.Action.SYNC);
        minecraft.setScreen(new ChronoRemoteChunkScreen(linked.pos()));
    }

    private static void openRepository() {
        send(ChronoRemoteActionPacket.Action.SYNC);
        Minecraft.getInstance().setScreen(new ChronoRemoteRepositoryScreen());
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

    private double sliceStep() {
        return 2.0D * Math.PI / options.size();
    }

    /** Center angle of option index's slice; option 0 sits at the top, the rest clockwise. */
    private double sliceAngle(int index) {
        return -Math.PI / 2.0D + sliceStep() * index;
    }

    @Nullable
    private Option hovered(double mouseX, double mouseY) {
        double dx = mouseX - centerX();
        double dy = mouseY - centerY();
        if (dx * dx + dy * dy < INNER_RADIUS * INNER_RADIUS) return null;

        double step = sliceStep();
        double angle = Math.atan2(dy, dx) + Math.PI / 2.0D + step / 2.0D;
        angle = ((angle % (2.0D * Math.PI)) + 2.0D * Math.PI) % (2.0D * Math.PI);
        return options.get((int) (angle / step) % options.size());
    }

    /** Fills the ring segment between the two radii from angle start to end (radians, clockwise on screen). */
    private static void fillSlice(GuiGraphics guiGraphics, float centerX, float centerY, float innerRadius, float outerRadius,
                                  double start, double end, int color) {
        Matrix4f matrix = guiGraphics.pose().last().pose();
        int segments = Math.max(1, (int) Math.ceil((end - start) / SEGMENT_ANGLE));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= segments; i++) {
            double angle = start + (end - start) * i / segments;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            buffer.addVertex(matrix, centerX + cos * outerRadius, centerY + sin * outerRadius, 0.0F).setColor(color);
            buffer.addVertex(matrix, centerX + cos * innerRadius, centerY + sin * innerRadius, 0.0F).setColor(color);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        Option hovered = hovered(mouseX, mouseY);
        double step = sliceStep();
        double gap = options.size() > 1 ? SLICE_GAP : 0.0D;
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            double angle = sliceAngle(i);
            int color = option == armed ? SLICE_ARMED_COLOR : (option == hovered ? SLICE_HOVER_COLOR : SLICE_COLOR);
            fillSlice(guiGraphics, centerX(), centerY(), INNER_RADIUS, OUTER_RADIUS,
                    angle - step / 2.0D + gap, angle + step / 2.0D - gap, color);
            renderLabel(guiGraphics, option, angle, step);
        }

        fillSlice(guiGraphics, centerX(), centerY(), 0.0F, HUB_RADIUS, 0.0D, 2.0D * Math.PI, HUB_COLOR);
        if (hovered != null && hovered.hint != Component.empty()) {
            guiGraphics.drawCenteredString(font, hovered.hint, centerX(), centerY() + OUTER_RADIUS + HINT_GAP, HINT_COLOR);
        }
    }

    /** Draws an option's name, wrapped to fit its slice, with its state or confirmation prompt beneath. */
    private void renderLabel(GuiGraphics guiGraphics, Option option, double angle, double step) {
        double middleRadius = (INNER_RADIUS + OUTER_RADIUS) / 2.0D;
        int x = centerX() + (int) Math.round(Math.cos(angle) * middleRadius);
        int y = centerY() + (int) Math.round(Math.sin(angle) * middleRadius);

        // The slice narrows toward the hub, so wrap to the chord at the middle radius.
        double chord = 2.0D * middleRadius * Math.sin(Math.min(step, Math.PI) / 2.0D);
        int maxWidth = (int) Math.min(OUTER_RADIUS - INNER_RADIUS, chord) - LABEL_PADDING;
        List<FormattedCharSequence> lines = font.split(option.label, Math.max(40, maxWidth));
        if (lines.size() > MAX_LABEL_LINES) lines = lines.subList(0, MAX_LABEL_LINES);

        Component detail = null;
        int detailColor = TEXT_COLOR;
        if (option == armed) {
            detail = Component.translatable("gui.temporalindustries.chrono_remote.click_to_confirm");
        } else if (option.state != null) {
            boolean on = option.state.getAsBoolean();
            detail = Component.translatable(on ? "gui.temporalindustries.chrono_remote.on" : "gui.temporalindustries.chrono_remote.off");
            detailColor = on ? ON_COLOR : OFF_COLOR;
        }

        int rows = lines.size() + (detail == null ? 0 : 1);
        int lineY = y - rows * font.lineHeight / 2;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(font, line, x - font.width(line) / 2, lineY, TEXT_COLOR);
            lineY += font.lineHeight;
        }
        if (detail != null) {
            guiGraphics.drawCenteredString(font, detail, x, lineY, detailColor);
        }
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
