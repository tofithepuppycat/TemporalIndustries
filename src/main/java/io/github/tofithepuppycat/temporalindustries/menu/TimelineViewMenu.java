package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import net.minecraft.core.BlockPos;

/** Implemented by any menu whose screen shows {@link io.github.tofithepuppycat.temporalindustries.client.timeline.TimelineGraphWidget}
 * — lets the timeline network packets validate/handle a request without knowing which concrete
 * machine type (Time Machine, Chronosphere, ...) opened the menu. */
public interface TimelineViewMenu {
    BlockPos getBlockPos();
    TimelineViewProvider getTimelineProvider();
}
