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
import org.jetbrains.annotations.Nullable;

/**
 * A movable panel layered over a container screen and owned by a {@link WindowManager}. It draws
 * a frame with a title and close button (base.png unless a {@link Panel} says otherwise, which may
 * also be drawn at any size by {@link VanillaBackground}); subclasses draw and handle input for
 * the area inside, measured from {@link #x}/{@link #y} (the frame artwork's top-left corner).
 * A pinned window remembers its position and is reopened there whenever a screen is opened.
 */
public abstract class GuiWindow {
    private static final int CANVAS_SIZE = 256;
    /** base.png's artwork sits inset this many pixels within its 256x256 canvas. */
    private static final int ART_MARGIN = 16;
    /** Width and height of the default panel's usable area. */
    public static final int SIZE = CANVAS_SIZE - 2 * ART_MARGIN;

    /**
     * A window's frame and the usable area it encloses. A textured panel is drawn from a 256x256
     * canvas, {@code margin} being how far the artwork is inset from the canvas corner; a panel
     * without a texture is generated at its size in the vanilla container style.
     */
    protected record Panel(@Nullable ResourceLocation texture, int margin, int width, int height) {
        /** A vanilla-style panel generated at width x height, so any size is possible. */
        protected static Panel generated(int width, int height) {
            return new Panel(null, 0, width, height);
        }
    }

    protected static final Panel DEFAULT_PANEL = new Panel(ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/base.png"), ART_MARGIN, SIZE, SIZE);
    protected static final Panel MEDIUM_PANEL = new Panel(ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/base_medium.png"), 0, 176, 166);

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
    protected final int panelWidth;
    protected final int panelHeight;

    private final Panel panel;
    private final Component title;
    private WindowManager manager;
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    protected GuiWindow(Component title) {
        this(title, DEFAULT_PANEL);
    }

    protected GuiWindow(Component title, Panel panel) {
        this.title = title;
        this.panel = panel;
        this.panelWidth = panel.width();
        this.panelHeight = panel.height();
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
        x = Math.max(0, Math.min(newX, manager.screenWidth() - panelWidth));
        y = Math.max(0, Math.min(newY, manager.screenHeight() - panelHeight));
    }

    final boolean contains(double mouseX, double mouseY) {
        return isOver(mouseX, mouseY, x, y, panelWidth, panelHeight);
    }

    final void renderWindow(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (panel.texture() == null) {
            VanillaBackground.render(guiGraphics, x, y, panelWidth, panelHeight);
        } else {
            int margin = panel.margin();
            guiGraphics.blit(panel.texture(), x - margin, y - margin, 0, 0, panelWidth + 2 * margin, panelHeight + 2 * margin);
        }
        drawCenteredNoShadow(guiGraphics, title, x + panelWidth / 2, y + TITLE_Y_OFFSET, TEXT_PRIMARY);

        int closeX = closeX();
        int closeY = closeY();
        drawShadedBox(guiGraphics, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE,
                isOverClose(mouseX, mouseY) ? 0xFFB04A4A : 0xFF6A6A6A);
        drawCenteredNoShadow(guiGraphics, "x", closeX + CLOSE_SIZE / 2 + 1, closeY + 1, 0xFFFFFFFF);

        int pinX = pinX();
        int pinBase = isPinned() ? 0xFF4A9A5A : 0xFF6A6A6A;
        drawShadedBox(guiGraphics, pinX, closeY, CLOSE_SIZE, CLOSE_SIZE,
                isOverPin(mouseX, mouseY) ? brighten(pinBase) : pinBase);
        drawCenteredNoShadow(guiGraphics, "P", pinX + CLOSE_SIZE / 2 + 1, closeY + 1, 0xFFFFFFFF);

        renderContents(guiGraphics, mouseX, mouseY);
    }

    final void press(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverClose(mouseX, mouseY)) {
            close();
        } else if (button == 0 && isOverPin(mouseX, mouseY)) {
            togglePin();
        } else if (button == 0 && isOver(mouseX, mouseY, x, y, panelWidth, TITLE_BAR_HEIGHT)) {
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
        return x + panelWidth - CLOSE_SIZE - CLOSE_X_INSET;
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
        drawShadedBox(guiGraphics, buttonX, buttonY, width, height, bg);
        drawCenteredNoShadow(guiGraphics, label, buttonX + width / 2, buttonY + (height - 8) / 2, 0xFFFFFFFF);
    }

    /**
     * A button face in the style of the mod's button textures: a black outline, a lighter edge along
     * the top and left and a darker one along the bottom and right.
     */
    private static void drawShadedBox(GuiGraphics guiGraphics, int boxX, int boxY, int width, int height, int fill) {
        int right = boxX + width;
        int bottom = boxY + height;
        guiGraphics.fill(boxX, boxY, right, bottom, COLOR_BORDER);
        guiGraphics.fill(boxX + 1, boxY + 1, right - 1, bottom - 1, shade(fill, 0.75F));
        guiGraphics.fill(boxX + 1, boxY + 1, right - 1, boxY + 2, shade(fill, 1.5F));
        guiGraphics.fill(boxX + 1, boxY + 1, boxX + 2, bottom - 1, shade(fill, 1.5F));
        guiGraphics.fill(boxX + 2, boxY + 2, right - 2, bottom - 2, fill);
    }

    private static int shade(int color, float factor) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, Math.round((color & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    protected final void drawDivider(GuiGraphics guiGraphics, int dividerY, int inset) {
        guiGraphics.fill(x + inset, dividerY, x + panelWidth - inset, dividerY + 1, COLOR_DIVIDER);
    }

    private static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 30);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 30);
        int b = Math.min(255, (color & 0xFF) + 30);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
