package io.github.tofithepuppycat.temporalindustries.block.entity;

/** How a timeline machine reacts to redstone: not at all, or by returning to its pinned commit. */
public enum RedstoneMode {
    /** Redstone has no effect. */
    IGNORE("ignore"),
    /** Returns to the pinned commit when a signal starts. */
    SIGNAL_START("signal_start"),
    /** Returns to the pinned commit when the signal ends. */
    SIGNAL_END("signal_end");

    private final String key;

    RedstoneMode(String key) {
        this.key = key;
    }

    /** Suffix of this mode's translation keys. */
    public String key() {
        return key;
    }

    /** Whether the signal changing to powered triggers a return. */
    public boolean triggersOn(boolean powered) {
        return switch (this) {
            case IGNORE -> false;
            case SIGNAL_START -> powered;
            case SIGNAL_END -> !powered;
        };
    }

    public static RedstoneMode byOrdinal(int ordinal) {
        RedstoneMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IGNORE;
    }
}
