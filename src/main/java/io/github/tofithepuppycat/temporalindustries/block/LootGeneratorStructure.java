package io.github.tofithepuppycat.temporalindustries.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/** Defines the 3(wide) x 2(tall) x 2(deep) frame shape required around a {@link LootGenerator}
 * controller for it to be formed. Offsets are authored for a controller facing {@link Direction#NORTH}
 * - the controller occupies the center of the front row at relative (0,0,0), with the frame's depth
 * extending south (away from its front face) - and are rotated to match the controller's actual
 * {@link LootGenerator#FACING} so the recognized shape lines up with the front face the block model
 * actually renders. */
public final class LootGeneratorStructure {
    private static final List<BlockPos> FRAME_OFFSETS = buildFrameOffsets();

    private LootGeneratorStructure() {}

    private static List<BlockPos> buildFrameOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    offsets.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(offsets);
    }

    /** The 11 frame positions, in world space, for a controller placed at {@code controllerPos} and
     * facing {@code facing}, matching the same y-axis rotation the blockstate applies to the model. */
    public static List<BlockPos> framePositions(BlockPos controllerPos, Direction facing) {
        List<BlockPos> positions = new ArrayList<>(FRAME_OFFSETS.size());
        for (BlockPos offset : FRAME_OFFSETS) {
            positions.add(controllerPos.offset(rotate(offset, facing)));
        }
        return positions;
    }

    /** Rotates a (x,z) offset authored for {@link Direction#NORTH} around the y-axis to match the
     * given facing, using the same clockwise-from-above convention as the blockstate's "y" rotation
     * (north=0, east=90, south=180, west=270). Leaves the vertical (y) component untouched. */
    private static BlockPos rotate(BlockPos offset, Direction facing) {
        int x = offset.getX();
        int z = offset.getZ();
        return switch (facing) {
            case NORTH -> offset;
            case EAST -> new BlockPos(-z, offset.getY(), x);
            case SOUTH -> new BlockPos(-x, offset.getY(), -z);
            case WEST -> new BlockPos(z, offset.getY(), -x);
            default -> offset;
        };
    }
}
