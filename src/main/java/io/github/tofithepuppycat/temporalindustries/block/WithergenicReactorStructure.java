package io.github.tofithepuppycat.temporalindustries.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines the 3x3x3 shell required around a {@link WithergenicReactor} controller: the controller
 * sits in the middle of the front face, the 18 positions in the top and bottom layers are Machine
 * Frames and the 7 remaining positions in the middle layer are Tinted Glass. The centre of the
 * cube is the open chamber the captured Wither floats in.
 */
public final class WithergenicReactorStructure {
    private static final List<BlockPos> FRAME_OFFSETS = buildOffsets(false);
    private static final List<BlockPos> GLASS_OFFSETS = buildOffsets(true);

    private WithergenicReactorStructure() {}

    private static List<BlockPos> buildOffsets(boolean middleLayer) {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = 0; z <= 2; z++) {
                    boolean chamber = x == 0 && y == 0 && z == 1;
                    boolean controller = x == 0 && y == 0 && z == 0;
                    if (chamber || controller || (y == 0) != middleLayer) continue;
                    offsets.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(offsets);
    }

    /** The 18 Machine Frame positions in world space for a controller at {@code controllerPos}. */
    public static List<BlockPos> framePositions(BlockPos controllerPos, Direction facing) {
        return worldPositions(FRAME_OFFSETS, controllerPos, facing);
    }

    /** The 7 Tinted Glass positions in world space for a controller at {@code controllerPos}. */
    public static List<BlockPos> glassPositions(BlockPos controllerPos, Direction facing) {
        return worldPositions(GLASS_OFFSETS, controllerPos, facing);
    }

    private static List<BlockPos> worldPositions(List<BlockPos> offsets, BlockPos controllerPos, Direction facing) {
        List<BlockPos> positions = new ArrayList<>(offsets.size());
        for (BlockPos offset : offsets) {
            positions.add(controllerPos.offset(rotate(offset, facing)));
        }
        return positions;
    }

    /** The open centre of the cube, where the captured Wither is rendered as particles. */
    public static BlockPos chamberPosition(BlockPos controllerPos, Direction facing) {
        return controllerPos.offset(rotate(new BlockPos(0, 0, 1), facing));
    }

    /**
     * Rotates an (x,z) offset authored for {@link Direction#NORTH} around the y-axis to match
     * facing.
     */
    private static BlockPos rotate(BlockPos offset, Direction facing) {
        int x = offset.getX();
        int z = offset.getZ();
        return switch (facing) {
            case EAST -> new BlockPos(-z, offset.getY(), x);
            case SOUTH -> new BlockPos(-x, offset.getY(), -z);
            case WEST -> new BlockPos(z, offset.getY(), -x);
            default -> offset;
        };
    }
}
