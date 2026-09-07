package io.github.tofithepuppycat.temporalindustries.menu;

import io.github.tofithepuppycat.temporalindustries.block.entity.TimelineViewProvider;
import net.minecraft.core.BlockPos;

/** Implemented by any menu whose screen shows a timeline graph, so network packets can
 * validate/handle requests without knowing which concrete machine type opened the menu. */
public interface TimelineViewMenu {
    BlockPos getBlockPos();
    TimelineViewProvider getTimelineProvider();
}
