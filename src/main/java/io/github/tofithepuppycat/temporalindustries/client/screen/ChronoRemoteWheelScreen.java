package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.network.ChronoRemoteActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Selection wheel of the Chrono Remote Control: opens while right-click is held, the option the
 * cursor points at runs when the button is released.
 */
public class ChronoRemoteWheelScreen extends Screen {
    private static final ChronoRemoteActionPacket.Action[] ACTIONS = ChronoRemoteActionPacket.Action.values();

    private static final int WHEEL_RADIUS = 64;
    private static final int DEAD_ZONE = 18;
    private static final int TILE_WIDTH = 84;
    private static final int TILE_HEIGHT = 24;

    private static final int TILE_COLOR = 0xB0202028;
    private static final int TILE_HOVER_COLOR = 0xE0546E9E;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    public ChronoRemoteWheelScreen() {
        super(Component.translatable("gui.temporalindustries.chrono_remote.title"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new ChronoRemoteWheelScreen());
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
        return -Math.PI / 2.0D + 2.0D * Math.PI * index / ACTIONS.length;
    }

    @Nullable
    private ChronoRemoteActionPacket.Action hovered(double mouseX, double mouseY) {
        double dx = mouseX - centerX();
        double dy = mouseY - centerY();
        if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) return null;

        double step = 2.0D * Math.PI / ACTIONS.length;
        double angle = Math.atan2(dy, dx) + Math.PI / 2.0D + step / 2.0D;
        angle = ((angle % (2.0D * Math.PI)) + 2.0D * Math.PI) % (2.0D * Math.PI);
        return ACTIONS[(int) (angle / step) % ACTIONS.length];
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        ChronoRemoteActionPacket.Action hovered = hovered(mouseX, mouseY);
        for (int i = 0; i < ACTIONS.length; i++) {
            int x = centerX() + (int) Math.round(Math.cos(tileAngle(i)) * WHEEL_RADIUS);
            int y = centerY() + (int) Math.round(Math.sin(tileAngle(i)) * WHEEL_RADIUS);
            int left = x - TILE_WIDTH / 2;
            int top = y - TILE_HEIGHT / 2;

            guiGraphics.fill(left, top, left + TILE_WIDTH, top + TILE_HEIGHT, ACTIONS[i] == hovered ? TILE_HOVER_COLOR : TILE_COLOR);
            guiGraphics.drawCenteredString(font, ACTIONS[i].label(), x, y - font.lineHeight / 2, TEXT_COLOR);
        }

        guiGraphics.fill(centerX() - 2, centerY() - 2, centerX() + 2, centerY() + 2, TEXT_COLOR);
    }

    private void choose(double mouseX, double mouseY) {
        ChronoRemoteActionPacket.Action action = hovered(mouseX, mouseY);
        if (action != null) PacketDistributor.sendToServer(new ChronoRemoteActionPacket(action));
        onClose();
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
