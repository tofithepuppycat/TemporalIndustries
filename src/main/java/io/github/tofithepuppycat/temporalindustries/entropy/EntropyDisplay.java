package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Locale;
import java.util.Map;

/**
 * Formatting for the two entropy scales shown to players: liquid amounts in mB (cells, the anchor,
 * machine tanks) and the machine order/chaos balance.
 */
public final class EntropyDisplay {
    public static final TextColor ORDER_COLOR = TextColor.fromRgb(0xcfa0f3);
    public static final TextColor CHAOS_COLOR = TextColor.fromRgb(0x68f6ff);
    public static final TextColor FE_COLOR = TextColor.fromRgb(0xffaa00);

    private static final Map<String, TextColor> TOKEN_COLORS = Map.of(
            "ORD", ORDER_COLOR,
            "CHS", CHAOS_COLOR,
            "FE", FE_COLOR
    );

    private EntropyDisplay() {}

    /**
     * Machine order/chaos balance (Chronovault, Chronosphere, Chronodial) uses a wider 0-10000
     * scale, shown with two decimals - e.g. a raw amount of 5000 reads as "50.00".
     */
    public static String formatBalance(int raw) {
        return String.format(Locale.ROOT, "%.2f", raw / 100.0);
    }

    /**
     * Liquid Order/Chaos amounts (cells, the anchor, machine tanks) are plain mB and are shown as
     * such, group-separated - e.g. 5000 reads as "5,000".
     */
    public static String formatFluid(int millibuckets) {
        return String.format(Locale.ROOT, "%,d", millibuckets);
    }

    /** The colored "ORD" / "CHS" unit suffix, e.g. to append after a formatted amount. */
    public static MutableComponent unit(EntropyType type) {
        TextColor color = type == EntropyType.ORDER ? ORDER_COLOR : CHAOS_COLOR;
        String text = type == EntropyType.ORDER ? " ORD" : " CHS";
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color));
    }

    /**
     * Rebuilds {@code text} as a component, recoloring any "ORD" / "CHS" / "FE" tokens with their
     * shared colors and everything else with {@code baseColor}.
     */
    public static MutableComponent colorTokens(String text, ChatFormatting baseColor) {
        MutableComponent result = Component.empty();
        int i = 0;
        while (i < text.length()) {
            int matchIdx = -1;
            String matchToken = null;
            for (String token : TOKEN_COLORS.keySet()) {
                int idx = nextWordBoundaryMatch(text, token, i);
                if (idx != -1 && (matchIdx == -1 || idx < matchIdx)) {
                    matchIdx = idx;
                    matchToken = token;
                }
            }
            if (matchIdx == -1) {
                result.append(Component.literal(text.substring(i)).withStyle(baseColor));
                break;
            }
            if (matchIdx > i) {
                result.append(Component.literal(text.substring(i, matchIdx)).withStyle(baseColor));
            }
            result.append(Component.literal(matchToken).setStyle(Style.EMPTY.withColor(TOKEN_COLORS.get(matchToken))));
            i = matchIdx + matchToken.length();
        }
        return result;
    }

    /**
     * Finds the next occurrence of {@code token} at or after {@code from} that isn't part of a
     * larger word, so short tokens like "FE" don't match inside unrelated text.
     */
    private static int nextWordBoundaryMatch(String text, String token, int from) {
        int idx = text.indexOf(token, from);
        while (idx != -1) {
            boolean beforeOk = idx == 0 || !Character.isLetter(text.charAt(idx - 1));
            int after = idx + token.length();
            boolean afterOk = after >= text.length() || !Character.isLetter(text.charAt(after));
            if (beforeOk && afterOk) return idx;
            idx = text.indexOf(token, idx + 1);
        }
        return -1;
    }
}
