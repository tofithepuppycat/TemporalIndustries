package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.network.chat.Component;

import java.util.List;

/** Implemented by block entities that can report their entropy state to the Entropy Glasses overlay. */
public interface EntropyInfoProvider {
    List<Component> getEntropyTooltip();

    /** Whether this block also has an order/chaos balance to render as the bidirectional bar
     * used by the Chronosphere/Chronovault GUIs (see AbstractTimelineMachineBlockEntity). */
    default boolean hasEntropyBalance() {
        return false;
    }

    default int getEntropyBalance() {
        return 0;
    }

    default int getEntropyBalanceMax() {
        return 1;
    }

    /** How fast the balance is currently drifting, in display-scale units (see {@link EntropyDisplay})
     * per second — positive toward chaos, negative toward order, 0 when settled. Purely informational,
     * shown by the Entropy Glasses overlay. */
    default float getEntropyRatePerSecond() {
        return 0f;
    }
}
