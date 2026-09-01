package io.github.tofithepuppycat.temporalindustries.block;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Defines the fixed, unrotated 3(wide) x 2(tall) x 2(deep) frame shape required around a
 * {@link LootGenerator} controller for it to be formed. The controller occupies the corner cell at
 * relative (0,0,0); every other cell in the box must hold a {@link MachineFrame} block. */
public final class LootGeneratorStructure {
    private static final List<BlockPos> FRAME_OFFSETS = buildFrameOffsets();

    private LootGeneratorStructure() {}

    private static List<BlockPos> buildFrameOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    offsets.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(offsets);
    }

    /** The 11 frame positions, in world space, for a controller placed at {@code controllerPos}. */
    public static List<BlockPos> framePositions(BlockPos controllerPos) {
        List<BlockPos> positions = new ArrayList<>(FRAME_OFFSETS.size());
        for (BlockPos offset : FRAME_OFFSETS) {
            positions.add(controllerPos.offset(offset));
        }
        return positions;
    }
}
