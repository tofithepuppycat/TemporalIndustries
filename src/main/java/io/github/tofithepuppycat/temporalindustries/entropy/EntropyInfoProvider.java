package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.network.chat.Component;

import java.util.List;

/** Implemented by block entities that can report their entropy state to the Entropy Goggles overlay. */
public interface EntropyInfoProvider {
    List<Component> getEntropyTooltip();
}
