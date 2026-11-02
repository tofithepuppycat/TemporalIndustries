package io.github.tofithepuppycat.temporalindustries.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines the spherical shell required around a {@link WithergenicReactor} controller: a hollow
 * voxel sphere of radius 2.5 whose open 3x3x3 centre is the chamber the captured Wither floats in.
 * The controller sits in the middle of the front of the shell, the shell's middle layer is Tinted
 * Glass and every other shell position is a Machine Frame.
 */
public final class WithergenicReactorStructure {
    private static final double RADIUS_SQUARED = 2.5 * 2.5;
    /** Distance from the controller to the chamber centre, along the facing direction. */
    private static final int CHAMBER_DEPTH = 2;

    private static final List<BlockPos> FRAME_OFFSETS = buildOffsets(false);
    private static final List<BlockPos> GLASS_OFFSETS = buildOffsets(true);
    private static final List<BlockPos> INTERIOR_OFFSETS = buildInterior();

    private WithergenicReactorStructure() {}

    private static boolean insideSphere(int x, int y, int z) {
        return x * x + y * y + z * z <= RADIUS_SQUARED;
    }

    /** A sphere cell with at least one face-neighbour outside the sphere. */
    private static boolean onShell(int x, int y, int z) {
        return insideSphere(x, y, z)
                && !(insideSphere(x + 1, y, z) && insideSphere(x - 1, y, z)
                && insideSphere(x, y + 1, z) && insideSphere(x, y - 1, z)
                && insideSphere(x, y, z + 1) && insideSphere(x, y, z - 1));
    }

    private static List<BlockPos> buildInterior() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (insideSphere(x, y, z) && !onShell(x, y, z)) offsets.add(new BlockPos(x, y, z + CHAMBER_DEPTH));
                }
            }
        }
        return List.copyOf(offsets);
    }

    private static List<BlockPos> buildOffsets(boolean middleLayer) {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    boolean controller = x == 0 && y == 0 && z == -CHAMBER_DEPTH;
                    if (controller || !onShell(x, y, z) || (y == 0) != middleLayer) continue;
                    offsets.add(new BlockPos(x, y, z + CHAMBER_DEPTH));
                }
            }
        }
        return List.copyOf(offsets);
    }

    /** The Machine Frame positions in world space for a controller at {@code controllerPos}. */
    public static List<BlockPos> framePositions(BlockPos controllerPos, Direction facing) {
        return worldPositions(FRAME_OFFSETS, controllerPos, facing);
    }

    /** The Tinted Glass positions in world space for a controller at {@code controllerPos}. */
    public static List<BlockPos> glassPositions(BlockPos controllerPos, Direction facing) {
        return worldPositions(GLASS_OFFSETS, controllerPos, facing);
    }

    /** The 3x3x3 chamber inside the shell, which has to stay empty. */
    public static List<BlockPos> interiorPositions(BlockPos controllerPos, Direction facing) {
        return worldPositions(INTERIOR_OFFSETS, controllerPos, facing);
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
        return controllerPos.offset(rotate(new BlockPos(0, 0, CHAMBER_DEPTH), facing));
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
