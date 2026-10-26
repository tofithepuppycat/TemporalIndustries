package io.github.tofithepuppycat.temporalindustries.client.window;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/**
 * A movable panel layered over a container screen and owned by a {@link WindowManager}. It draws
 * the shared base.png frame with a title and close button; subclasses draw and handle input for
 * the area inside, measured from {@link #x}/{@link #y} (the frame artwork's top-left corner).
 * A pinned window remembers its position and is reopened there whenever a screen is opened.
 */
public abstract class GuiWindow {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/base.png");

    private static final int CANVAS_SIZE = 256;
    /** base.png's artwork sits inset this many pixels within its 256x256 canvas. */
    private static final int ART_MARGIN = 16;
    /** Width and height of a window's usable area. */
    public static final int SIZE = CANVAS_SIZE - 2 * ART_MARGIN;

    protected static final int TEXT_PRIMARY = 0xFF2B2B2B;
    protected static final int TEXT_SECONDARY = 0xFF5A5A5A;
    protected static final int TEXT_MUTED = 0xFF7A7A7A;
    protected static final int COLOR_BORDER = 0xFF000000;
    protected static final int COLOR_DIVIDER = 0x30000000;

    private static final int TITLE_Y_OFFSET = 4;
    private static final int TITLE_BAR_HEIGHT = 14;
    private static final int CLOSE_SIZE = 9;
    private static final int CLOSE_X_INSET = 5;
    private static final int CLOSE_Y_OFFSET = 3;
    private static final int PIN_GAP = 2;

    /** Pinned window positions by window class, kept for the rest of the client session. */
    private static final Map<String, int[]> PINNED = new HashMap<>();

    protected final Font font = Minecraft.getInstance().font;
    protected int x;
    protected int y;

    private final Component title;
    private WindowManager manager;
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    protected GuiWindow(Component title) {
        this.title = title;
    }

    /** Draws the window's own content; called after the frame, title and close button. */
    protected abstract void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY);

    protected boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    protected boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    protected boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    protected boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        return false;
    }

    /** Called once per client tick while the window is open. */
    protected void tick() {}

    /** Called each time the window is opened, before its first render. */
    protected void onOpened() {}

    /** Called each time the window is closed, however that happens. */
    protected void onClosed() {}

    /** Closes the window and unpins it. */
    public final void close() {
        PINNED.remove(pinKey());
        if (manager != null) manager.close(this);
    }

    public final boolean isPinned() {
        return PINNED.containsKey(pinKey());
    }

    static int[] pinnedPosition(GuiWindow window) {
        return PINNED.get(window.pinKey());
    }

    private String pinKey() {
        return getClass().getName();
    }

    private void togglePin() {
        if (isPinned()) {
            PINNED.remove(pinKey());
        } else {
            PINNED.put(pinKey(), new int[] {x, y});
        }
    }

    final void attach(WindowManager manager) {
        this.manager = manager;
    }

    final void moveTo(int newX, int newY) {
        x = Math.max(0, Math.min(newX, manager.screenWidth() - SIZE));
        y = Math.max(0, Math.min(newY, manager.screenHeight() - SIZE));
    }

    final boolean contains(double mouseX, double mouseY) {
        return isOver(mouseX, mouseY, x, y, SIZE, SIZE);
    }

    final void renderWindow(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.blit(BASE_TEXTURE, x - ART_MARGIN, y - ART_MARGIN, 0, 0, CANVAS_SIZE, CANVAS_SIZE);
        drawCenteredNoShadow(guiGraphics, title, x + SIZE / 2, y + TITLE_Y_OFFSET, TEXT_PRIMARY);

        int closeX = closeX();
        int closeY = closeY();
        guiGraphics.fill(closeX, closeY, closeX + CLOSE_SIZE, closeY + CLOSE_SIZE, COLOR_BORDER);
        guiGraphics.fill(closeX + 1, closeY + 1, closeX + CLOSE_SIZE - 1, closeY + CLOSE_SIZE - 1,
                isOverClose(mouseX, mouseY) ? 0xFFB04A4A : 0xFF6A6A6A);
        drawCenteredNoShadow(guiGraphics, "x", closeX + CLOSE_SIZE / 2 + 1, closeY + 1, 0xFFFFFFFF);

        int pinX = pinX();
        guiGraphics.fill(pinX, closeY, pinX + CLOSE_SIZE, closeY + CLOSE_SIZE, COLOR_BORDER);
        int pinBase = isPinned() ? 0xFF4A9A5A : 0xFF6A6A6A;
        guiGraphics.fill(pinX + 1, closeY + 1, pinX + CLOSE_SIZE - 1, closeY + CLOSE_SIZE - 1,
                isOverPin(mouseX, mouseY) ? brighten(pinBase) : pinBase);
        drawCenteredNoShadow(guiGraphics, "P", pinX + CLOSE_SIZE / 2 + 1, closeY + 1, 0xFFFFFFFF);

        renderContents(guiGraphics, mouseX, mouseY);
    }

    final void press(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverClose(mouseX, mouseY)) {
            close();
        } else if (button == 0 && isOverPin(mouseX, mouseY)) {
            togglePin();
        } else if (button == 0 && isOver(mouseX, mouseY, x, y, SIZE, TITLE_BAR_HEIGHT)) {
            dragging = true;
            dragOffsetX = (int) mouseX - x;
            dragOffsetY = (int) mouseY - y;
        } else {
            mouseClicked(mouseX, mouseY, button);
        }
    }

    final void drag(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            moveTo((int) mouseX - dragOffsetX, (int) mouseY - dragOffsetY);
        } else {
            mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
    }

    final void release(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            if (isPinned()) PINNED.put(pinKey(), new int[] {x, y});
        } else {
            mouseReleased(mouseX, mouseY, button);
        }
    }

    final boolean scroll(double mouseX, double mouseY, double scrollY) {
        return mouseScrolled(mouseX, mouseY, scrollY);
    }

    final void tickWindow() {
        tick();
    }

    final void opened() {
        dragging = false;
        onOpened();
    }

    final void closed() {
        dragging = false;
        onClosed();
    }

    private int closeX() {
        return x + SIZE - CLOSE_SIZE - CLOSE_X_INSET;
    }

    private int pinX() {
        return closeX() - CLOSE_SIZE - PIN_GAP;
    }

    private boolean isOverPin(double mouseX, double mouseY) {
        return isOver(mouseX, mouseY, pinX(), closeY(), CLOSE_SIZE, CLOSE_SIZE);
    }

    private int closeY() {
        return y + CLOSE_Y_OFFSET;
    }

    private boolean isOverClose(double mouseX, double mouseY) {
        return isOver(mouseX, mouseY, closeX(), closeY(), CLOSE_SIZE, CLOSE_SIZE);
    }

    protected static boolean isOver(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    // GuiGraphics has no no-shadow overload of drawCenteredString/drawWordWrap; against the light
    // panel the shadow reads as ghosting.
    protected final void drawCenteredNoShadow(GuiGraphics guiGraphics, Component text, int centerX, int textY, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, textY, color, false);
    }

    protected final void drawCenteredNoShadow(GuiGraphics guiGraphics, String text, int centerX, int textY, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, textY, color, false);
    }

    protected final void drawWordWrapNoShadow(GuiGraphics guiGraphics, Component text, int textX, int textY,
                                              int maxWidth, int color) {
        int lineY = textY;
        for (FormattedCharSequence line : font.split(text, maxWidth)) {
            guiGraphics.drawString(font, line, textX, lineY, color, false);
            lineY += font.lineHeight;
        }
    }

    protected final void drawButton(GuiGraphics guiGraphics, int buttonX, int buttonY, int width, int height,
                                    Component label, int baseColor, boolean hovered) {
        int bg = hovered ? brighten(baseColor) : baseColor;
        guiGraphics.fill(buttonX, buttonY, buttonX + width, buttonY + height, COLOR_BORDER);
        guiGraphics.fill(buttonX + 1, buttonY + 1, buttonX + width - 1, buttonY + height - 1, bg);
        drawCenteredNoShadow(guiGraphics, label, buttonX + width / 2, buttonY + (height - 8) / 2, 0xFFFFFFFF);
    }

    protected final void drawDivider(GuiGraphics guiGraphics, int dividerY, int inset) {
        guiGraphics.fill(x + inset, dividerY, x + SIZE - inset, dividerY + 1, COLOR_DIVIDER);
    }

    private static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 30);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 30);
        int b = Math.min(255, (color & 0xFF) + 30);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
