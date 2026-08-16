package io.github.tofithepuppycat.temporalindustries.entropy;

/** ORD (order) and CHS (chaos), the two xp-like substances from IDEAS.md. */
public enum EntropyType {
    ORDER(0xFFFFFF),
    CHAOS(0x3E2A49);

    private final int color;

    EntropyType(int color) {
        this.color = color;
    }

    public int color() {
        return color;
    }
}
