package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Locale;

/** Entropy and entropy-storage capacities are all tracked internally as plain ints (order/chaos
 * cell contents, machine balance, …); this only changes how those raw values are shown to the
 * player — scaled down by 10 with one decimal, so e.g. a raw amount of 100 reads as "10.0". */
public final class EntropyDisplay {
    private static final TextColor ORDER_UNIT_COLOR = TextColor.fromRgb(0xcfa0f3);
    private static final TextColor CHAOS_UNIT_COLOR = TextColor.fromRgb(0x68f6ff);

    private EntropyDisplay() {}

    public static String format(int raw) {
        return String.format(Locale.ROOT, "%.1f", raw / 10.0);
    }

    /** The colored "ORD" / "CHS" unit suffix, e.g. to append after a formatted amount. */
    public static MutableComponent unit(EntropyType type) {
        TextColor color = type == EntropyType.ORDER ? ORDER_UNIT_COLOR : CHAOS_UNIT_COLOR;
        String text = type == EntropyType.ORDER ? " ORD" : " CHS";
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color));
    }
}
