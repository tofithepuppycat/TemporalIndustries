package io.github.tofithepuppycat.temporalindustries.client;

import java.util.Locale;

/**
 * Compact FE amounts with an SI prefix and unit, e.g. 17000 reads as "17.0 kFE" and 950 as "950 FE".
 */
public final class ShortEnergyDisplay {
    private static final String[] PREFIXES = {"", "k", "M", "G", "T", "P", "E"};

    private ShortEnergyDisplay() {}

    /** Shortened amount followed by its prefixed unit, e.g. "17.0 kFE". */
    public static String format(long fe) {
        return format(fe, true);
    }

    /** Shortened amount with the prefix but no FE unit, e.g. "17.0k". */
    public static String formatPlain(long fe) {
        return format(fe, false);
    }

    private static String format(long fe, boolean withUnit) {
        double value = Math.abs((double) fe);
        int unit = 0;
        while (value >= 1000 && unit < PREFIXES.length - 1) {
            value /= 1000;
            unit++;
        }
        // Rounding to one decimal can reach 1000 (e.g. 999,999 FE); promote to the next unit.
        if (unit > 0 && unit < PREFIXES.length - 1 && Math.round(value * 10) >= 10000) {
            value /= 1000;
            unit++;
        }
        String sign = fe < 0 ? "-" : "";
        String number = unit == 0
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
        if (withUnit) {
            return sign + number + " " + PREFIXES[unit] + "FE";
        }
        return sign + number + PREFIXES[unit];
    }
}
