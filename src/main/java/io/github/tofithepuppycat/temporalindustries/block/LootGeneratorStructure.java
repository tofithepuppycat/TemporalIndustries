package io.github.tofithepuppycat.temporalindustries.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/** Defines the 3x2x2 frame shape required around a {@link LootGenerator} controller for it to be
 * formed. Offsets are authored for a controller facing {@link Direction#NORTH} and rotated to
 * match the controller's actual {@link LootGenerator#FACING}. */
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

    /** The 11 frame positions in world space for a controller at {@code controllerPos} facing {@code facing}. */
    public static List<BlockPos> framePositions(BlockPos controllerPos, Direction facing) {
        List<BlockPos> positions = new ArrayList<>(FRAME_OFFSETS.size());
        for (BlockPos offset : FRAME_OFFSETS) {
            positions.add(controllerPos.offset(rotate(offset, facing)));
        }
        return positions;
    }

    /** Rotates an (x,z) offset authored for {@link Direction#NORTH} around the y-axis to match facing. */
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
