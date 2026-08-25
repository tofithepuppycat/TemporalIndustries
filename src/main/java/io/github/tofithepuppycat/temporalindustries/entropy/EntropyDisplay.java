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

    /** Whether {@link #formatFluidScaled} would show this amount k-scaled - callers use this to
     * decide which {@link #unit} suffix (plain or k-prefixed) belongs next to it. */
    public static boolean isThousands(int millibuckets) {
        return Math.abs(millibuckets) >= 1000;
    }

    /** Liquid amounts of a thousand mB or more are shown k-scaled to pair with the "k" prefixed
     * ORD/CHS unit, always to two decimals - e.g. 8000 reads as "8.00", 12500 as "12.50". Smaller
     * amounts are shown as-is, unscaled, since they'd otherwise round away to nothing. */
    public static String formatFluidScaled(int millibuckets) {
        if (!isThousands(millibuckets)) return formatFluid(millibuckets);
        return String.format(Locale.ROOT, "%,.2f", millibuckets / 1000.0);
    }

    /** The colored "ORD" / "CHS" unit suffix, e.g. to append after a formatted amount - "kORD" /
     * "kCHS" for amounts shown k-scaled by {@link #formatFluidScaled} (see {@link #isThousands}). */
    public static MutableComponent unit(EntropyType type, boolean thousands) {
        TextColor color = type == EntropyType.ORDER ? ORDER_UNIT_COLOR : CHAOS_UNIT_COLOR;
        String text = (thousands ? " k" : " ") + (type == EntropyType.ORDER ? "ORD" : "CHS");
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color));
    }

    public static MutableComponent unit(EntropyType type) {
        return unit(type, false);
    }

    /** "<amount>/<capacity>" plus the colored unit suffix, scaled off the current amount - not the
     * capacity - so a near-empty tank reads e.g. "500/8,000 CHS" rather than "0.50/8.00k CHS". */
    public static MutableComponent amountOverCapacity(int amount, int capacity, EntropyType type) {
        boolean thousands = isThousands(amount);
        String amountText = thousands ? formatFluidScaled(amount) : formatFluid(amount);
        String capacityText = thousands ? formatFluidScaled(capacity) : formatFluid(capacity);
        return Component.literal(amountText + "/" + capacityText).append(unit(type, thousands));
    }
}
