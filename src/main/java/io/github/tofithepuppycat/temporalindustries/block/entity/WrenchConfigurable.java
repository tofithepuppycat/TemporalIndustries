package io.github.tofithepuppycat.temporalindustries.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Implemented by block entities whose input/output links the Wrench can edit. */
public interface WrenchConfigurable {
    /**
     * Links {@code target} to this block, or unlinks it if it is already linked.
     *
     * @param output whether the player asked for the output role (sneaking); ignored by blocks
     *               with a single linkable role
     * @return the status message to show the player
     */
    Component wrenchLink(BlockPos target, boolean output);

    /** Removes every link this block holds. */
    Component wrenchClear();

    /** The positions currently linked as inputs, shown red while the Wrench has this block selected. */
    default List<BlockPos> wrenchInputs() {
        return List.of();
    }

    /** The positions currently linked as outputs, shown blue while the Wrench has this block selected. */
    default List<BlockPos> wrenchOutputs() {
        return List.of();
    }
}
