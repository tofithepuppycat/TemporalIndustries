package io.github.tofithepuppycat.temporalindustries.entropy;

import java.util.Locale;

/** Entropy and entropy-storage capacities are all tracked internally as plain ints (order/chaos
 * cell contents, machine balance, …); this only changes how those raw values are shown to the
 * player — scaled down by 10 with one decimal, so e.g. a raw amount of 100 reads as "10.0". */
public final class EntropyDisplay {
    private EntropyDisplay() {}

    public static String format(int raw) {
        return String.format(Locale.ROOT, "%.1f", raw / 10.0);
    }
}
