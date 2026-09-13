package io.github.tofithepuppycat.temporalindustries.block;

import net.minecraft.world.level.block.Block;

/** Sparse filler placed by {@link io.github.tofithepuppycat.temporalindustries.timeline.ChronoRiftFiller}
 * in Chrono Rift chunks with no tracked history at or before the visited moment. Purely cosmetic —
 * indestructible like a barrier, so players can't grief the rift's placeholder terrain. */
public class TemporalGlitchBlock extends Block {
    public TemporalGlitchBlock(Properties properties) {
        super(properties);
    }
}
