package io.github.tofithepuppycat.temporalindustries.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;

/** The {@link EntropicPylonItem} whose marks are blocks that store FE. */
public class EnergyPylonItem extends EntropicPylonItem {
    public EnergyPylonItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean isValidTarget(Level level, BlockPos pos) {
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) != null;
    }
}
