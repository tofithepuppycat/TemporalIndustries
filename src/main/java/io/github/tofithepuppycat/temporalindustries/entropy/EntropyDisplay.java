package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Locale;

/** Formatting for the two entropy scales shown to players: liquid amounts in mB (cells, the anchor,
 * machine tanks) and the machine order/chaos balance. */
public final class EntropyDisplay {
    private static final TextColor ORDER_UNIT_COLOR = TextColor.fromRgb(0xcfa0f3);
    private static final TextColor CHAOS_UNIT_COLOR = TextColor.fromRgb(0x68f6ff);

    private EntropyDisplay() {}

    /** Machine order/chaos balance (Chronovault, Chronosphere, Chronodial) uses a wider 0-10000
     * scale, shown with two decimals - e.g. a raw amount of 5000 reads as "50.00". Unrelated to the
     * mB amounts {@link #formatFluid(int)} handles. */
    public static String formatBalance(int raw) {
        return String.format(Locale.ROOT, "%.2f", raw / 100.0);
    }

    /** Liquid Order/Chaos amounts (cells, the anchor, machine tanks) are plain mB and are shown as
     * such, group-separated - e.g. 5000 reads as "5,000". */
    public static String formatFluid(int millibuckets) {
        return String.format(Locale.ROOT, "%,d", millibuckets);
    }

    /** The colored "ORD" / "CHS" unit suffix, e.g. to append after a formatted amount. */
    public static MutableComponent unit(EntropyType type) {
        TextColor color = type == EntropyType.ORDER ? ORDER_UNIT_COLOR : CHAOS_UNIT_COLOR;
        String text = type == EntropyType.ORDER ? " ORD" : " CHS";
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color));
    }
}
