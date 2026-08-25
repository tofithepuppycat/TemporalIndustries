package io.github.tofithepuppycat.temporalindustries.client;

import java.util.Locale;

/** Formatting for FE amounts shown in machine GUIs - group-separated, e.g. 1000000 reads as
 * "1,000,000". */
public final class EnergyDisplay {
    private EnergyDisplay() {}

    public static String format(long fe) {
        return String.format(Locale.ROOT, "%,d", fe);
    }
}
