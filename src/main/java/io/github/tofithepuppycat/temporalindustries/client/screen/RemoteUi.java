package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.client.window.VanillaBackground;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** The light vanilla container look shared by the Chrono Remote Control's screens. */
final class RemoteUi {
    static final int BUTTON_HEIGHT = 20;
    /** Native size of menu_button_small.png, the wide button the Chrono machines' screens use. */
    static final int MACHINE_BUTTON_WIDTH = 64;
    static final int MACHINE_BUTTON_HEIGHT = IconButtonRenderer.SIZE;
    private static final int MACHINE_BUTTON_TEXT_PADDING = 5;
    /** The machines' label colour, darker than the panel text so it reads on the button texture. */
    private static final int MACHINE_BUTTON_TEXT = 0xFF2B2B2B;

    static final int BACKDROP_COLOR = 0x90000000;
    /** Labels, as in vanilla container titles. */
    static final int TEXT_PRIMARY = 0xFF404040;
    static final int TEXT_SECONDARY = 0xFF555555;
    static final int TEXT_MUTED = 0xFF707070;
    static final int ACCENT_COLOR = 0xFF2A5E8C;
    static final int DIVIDER_COLOR = 0xFF8B8B8B;

    private static final int INSET_FILL = 0xFFB8B8B8;
    private static final int INSET_SHADOW = 0xFF373737;
    private static final int INSET_HIGHLIGHT = 0xFFFFFFFF;

    private static final ResourceLocation MACHINE_BUTTON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/menu_button_small.png");
    private static final ResourceLocation BUTTON_SPRITE = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation BUTTON_HOVER_SPRITE = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    private static final ResourceLocation BUTTON_DISABLED_SPRITE = ResourceLocation.withDefaultNamespace("widget/button_disabled");

    private RemoteUi() {}

    /** A vanilla button; {@code primary} is accepted for call sites that distinguish the main action. */
    static void drawButton(GuiGraphics guiGraphics, Font font, int x, int y, int width, Component label, boolean enabled,
                           boolean primary, int mouseX, int mouseY) {
        boolean hovered = enabled && isOver(mouseX, mouseY, x, y, width, BUTTON_HEIGHT);
        guiGraphics.blitSprite(!enabled ? BUTTON_DISABLED_SPRITE : hovered ? BUTTON_HOVER_SPRITE : BUTTON_SPRITE,
                x, y, width, BUTTON_HEIGHT);
        guiGraphics.drawCenteredString(font, label, x + width / 2, y + (BUTTON_HEIGHT - font.lineHeight) / 2,
                enabled ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    /**
     * The wide button of the Chrono machines' screens: its label on the left and, if given, its icon
     * at the right end; a label without an icon is centred. Dimmed while disabled, brightened on hover.
     */
    static void drawMachineButton(GuiGraphics guiGraphics, Font font, int x, int y, Component label,
                                  @Nullable ResourceLocation icon, boolean enabled, int mouseX, int mouseY) {
        guiGraphics.blit(MACHINE_BUTTON_TEXTURE, x, y, MACHINE_BUTTON_WIDTH, MACHINE_BUTTON_HEIGHT,
                0.0F, 0.0F, MACHINE_BUTTON_WIDTH, MACHINE_BUTTON_HEIGHT, MACHINE_BUTTON_WIDTH, MACHINE_BUTTON_HEIGHT);
        if (!enabled) {
            guiGraphics.fill(x, y, x + MACHINE_BUTTON_WIDTH, y + MACHINE_BUTTON_HEIGHT, 0x80000000);
        } else if (isOver(mouseX, mouseY, x, y, MACHINE_BUTTON_WIDTH, MACHINE_BUTTON_HEIGHT)) {
            guiGraphics.fill(x, y, x + MACHINE_BUTTON_WIDTH, y + MACHINE_BUTTON_HEIGHT, 0x40FFFFFF);
        }

        int textY = y + (MACHINE_BUTTON_HEIGHT - font.lineHeight) / 2 + 1;
        if (icon == null) {
            centered(guiGraphics, font, label, x + MACHINE_BUTTON_WIDTH / 2, textY, MACHINE_BUTTON_TEXT);
            return;
        }
        text(guiGraphics, font, label, x + MACHINE_BUTTON_TEXT_PADDING, textY, MACHINE_BUTTON_TEXT);
        IconButtonRenderer.renderIcon(guiGraphics, icon, x + MACHINE_BUTTON_WIDTH - IconButtonRenderer.SIZE, y);
    }

    /** The screen's main panel, in the vanilla container style. */
    static void drawBackground(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        VanillaBackground.render(guiGraphics, x, y, w, h);
    }

    /** A sunken area inside the panel: dark on its top and left, light on its bottom and right. */
    static void drawInset(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        guiGraphics.fill(x, y, x + w, y + h, INSET_FILL);
        guiGraphics.fill(x, y, x + w, y + 1, INSET_SHADOW);
        guiGraphics.fill(x, y, x + 1, y + h, INSET_SHADOW);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, INSET_HIGHLIGHT);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, INSET_HIGHLIGHT);
    }

    // Dark text on the light panel reads as ghosting with vanilla's default text shadow, so
    // labels are drawn without it.
    static void text(GuiGraphics guiGraphics, Font font, Component text, int x, int y, int color) {
        guiGraphics.drawString(font, text, x, y, color, false);
    }

    static void text(GuiGraphics guiGraphics, Font font, String text, int x, int y, int color) {
        guiGraphics.drawString(font, text, x, y, color, false);
    }

    static void text(GuiGraphics guiGraphics, Font font, FormattedCharSequence text, int x, int y, int color) {
        guiGraphics.drawString(font, text, x, y, color, false);
    }

    static void centered(GuiGraphics guiGraphics, Font font, Component text, int centerX, int y, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    static void wrapped(GuiGraphics guiGraphics, Font font, Component text, int x, int y, int width, int color) {
        for (FormattedCharSequence line : font.split(text, width)) {
            guiGraphics.drawString(font, line, x, y, color, false);
            y += font.lineHeight;
        }
    }

    static boolean isOver(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
