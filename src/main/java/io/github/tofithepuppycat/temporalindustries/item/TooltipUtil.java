package io.github.tofithepuppycat.temporalindustries.item;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;

import java.util.List;

/** Gates flavor/how-to-use tooltip lines behind Shift so tooltips stay short by default. Functional
 * status lines (contents, charge, mode) should stay outside this call, always visible. */
public final class TooltipUtil {
    private TooltipUtil() {}

    public static void appendDescription(List<Component> tooltip, String... translationKeys) {
        if (Screen.hasShiftDown()) {
            for (String key : translationKeys) {
                String text = Component.translatable(key).getString();
                tooltip.add(EntropyDisplay.colorTokens(text, ChatFormatting.GRAY));
            }
        } else {
            tooltip.add(Component.translatable("tooltip.temporalindustries.hold_shift")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }
}
