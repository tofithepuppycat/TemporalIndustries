package io.github.tofithepuppycat.temporalindustries.item;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Shared helper for gating flavor/how-to-use tooltip lines behind Shift, so item tooltips stay
 * short by default and only expand into the full description when the player asks for it. Status
 * lines (contents, charge, current mode, etc.) are functional and should stay outside this call,
 * always visible. */
public final class TooltipUtil {
    private TooltipUtil() {}

    public static void appendDescription(List<Component> tooltip, String... translationKeys) {
        if (Screen.hasShiftDown()) {
            for (String key : translationKeys) {
                tooltip.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
            }
        } else {
            tooltip.add(Component.translatable("tooltip.temporalindustries.hold_shift")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }
}
