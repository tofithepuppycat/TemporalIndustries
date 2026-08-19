package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.network.chat.Component;

import java.util.List;

/** Implemented by block entities that can report their entropy state to the Entropy Goggles overlay. */
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
}
