package io.github.tofithepuppycat.temporalindustries.client.window;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Owns the {@link GuiWindow}s open over one screen: keeps them stacked (last is frontmost), routes
 * input to the frontmost window under the cursor, and keeps the screen's own widgets from reacting
 * to input a window took.
 */
public final class WindowManager {
    /** Depth of the backmost window; above the screen's own widgets, below its tooltips. */
    private static final float BASE_Z = 100.0F;
    private static final float Z_STEP = 50.0F;
    /** Mouse position handed to content that something else is covering. */
    public static final int COVERED = Integer.MIN_VALUE / 2;

    private final List<GuiWindow> windows = new ArrayList<>();
    /** The window that took the current mouse press and receives its drag and release. */
    private GuiWindow pressed;

    public boolean isOpen(GuiWindow window) {
        return windows.contains(window);
    }

    /** Opens the window with its corner at (x, y), or just raises it if already open. */
    public void open(GuiWindow window, int x, int y) {
        if (windows.contains(window)) {
            raise(window);
            return;
        }
        window.attach(this);
        window.moveTo(x, y);
        windows.add(window);
        window.opened();
    }

    /** Opens each given window that is pinned, at its remembered position. */
    public void openPinned(GuiWindow... candidates) {
        for (GuiWindow window : candidates) {
            int[] position = GuiWindow.pinnedPosition(window);
            if (position != null) open(window, position[0], position[1]);
        }
    }

    /** Closes the window if it is the frontmost open one, raises it if it is behind others, opens it if closed. */
    public void toggle(GuiWindow window, int x, int y) {
        if (!windows.contains(window)) {
            open(window, x, y);
        } else if (windows.get(windows.size() - 1) == window) {
            window.close();
        } else {
            raise(window);
        }
    }

    public void close(GuiWindow window) {
        if (!windows.remove(window)) return;
        if (pressed == window) pressed = null;
        window.closed();
    }

    /** Closes every window without unpinning, so pinned ones reopen with the next screen. */
    public void closeAll() {
        for (GuiWindow window : new ArrayList<>(windows)) {
            close(window);
        }
    }

    public boolean isOverWindow(double mouseX, double mouseY) {
        for (GuiWindow window : windows) {
            if (window.contains(mouseX, mouseY)) return true;
        }
        return false;
    }

    public void tick() {
        for (GuiWindow window : new ArrayList<>(windows)) {
            window.tickWindow();
        }
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (int i = 0; i < windows.size(); i++) {
            GuiWindow window = windows.get(i);
            boolean covered = false;
            for (int j = i + 1; j < windows.size() && !covered; j++) {
                covered = windows.get(j).contains(mouseX, mouseY);
            }
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, BASE_Z + i * Z_STEP);
            window.renderWindow(guiGraphics, covered ? COVERED : mouseX, covered ? COVERED : mouseY);
            guiGraphics.pose().popPose();
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            GuiWindow window = windows.get(i);
            if (!window.contains(mouseX, mouseY)) continue;
            raise(window);
            pressed = window;
            window.press(mouseX, mouseY, button);
            // The press may have closed the window.
            if (!windows.contains(window)) pressed = null;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pressed == null) return false;
        pressed.drag(mouseX, mouseY, button, dragX, dragY);
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pressed == null) return false;
        GuiWindow window = pressed;
        pressed = null;
        window.release(mouseX, mouseY, button);
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            GuiWindow window = windows.get(i);
            if (window.contains(mouseX, mouseY)) {
                window.scroll(mouseX, mouseY, scrollY);
                return true;
            }
        }
        return false;
    }

    int screenWidth() {
        return window().getGuiScaledWidth();
    }

    int screenHeight() {
        return window().getGuiScaledHeight();
    }

    private static Window window() {
        return Minecraft.getInstance().getWindow();
    }

    private void raise(GuiWindow window) {
        if (windows.get(windows.size() - 1) == window) return;
        windows.remove(window);
        windows.add(window);
    }
}
