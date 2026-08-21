package io.github.tofithepuppycat.temporalindustries.entropy;

/** ORD (order) and CHS (chaos), the two xp-like substances from IDEAS.md. */
public enum EntropyType {
    ORDER(0xfecbe6, 0xcfa0f3),
    CHAOS(0x87f3fb, 0x009295);

    private final int color;
    private final int tint_to;

    EntropyType(int color, int tint_to) {
        this.color = color;
        this.tint_to = tint_to;
    }

    public int color() {
        return color;
    }

    public int tint_to() {
        return tint_to;
    }
}
