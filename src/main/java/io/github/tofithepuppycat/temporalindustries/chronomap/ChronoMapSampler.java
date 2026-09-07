package io.github.tofithepuppycat.temporalindustries.chronomap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Samples a chunk's surface into a small grid of packed vanilla map-color bytes (the same encoding
 * {@link MapColor#getPackedId} produces), used to paint a terrain thumbnail behind the
 * Chronosphere's chunk-claim map. A simplified, full-resolution port of {@code MapItem#update}'s
 * column-sampling loop rather than the real map-item rendering pipeline, which assumes a player is
 * physically holding a numbered map.
 */
public final class ChronoMapSampler {
    public static final int SIZE = 16;

    private ChronoMapSampler() {}

    /** SIZE x SIZE grid of packed map-color bytes for chunkPos, row-major (index = z * SIZE + x). */
    public static byte[] sampleChunk(ServerLevel level, ChunkPos chunkPos) {
        byte[] colors = new byte[SIZE * SIZE];
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        int minY = level.getMinBuildHeight();

        // Previous row's surface height per column, for the same brighter-up/darker-down banding vanilla maps use.
        int[] prevRowHeight = new int[SIZE];
        boolean[] havePrevRow = new boolean[SIZE];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < SIZE; lz++) {
            int worldZ = baseZ + lz;
            int prevColumnHeight = Integer.MIN_VALUE;
            for (int lx = 0; lx < SIZE; lx++) {
                int worldX = baseX + lx;
                int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);

                MapColor mapColor = MapColor.NONE;
                int surfaceY = topY;
                if (topY > minY + 1) {
                    pos.set(worldX, topY - 1, worldZ);
                    BlockState state = level.getBlockState(pos);
                    while (state.getMapColor(level, pos) == MapColor.NONE && pos.getY() > minY) {
                        pos.setY(pos.getY() - 1);
                        state = level.getBlockState(pos);
                    }
                    mapColor = state.getMapColor(level, pos);
                    surfaceY = pos.getY();
                }
                if (mapColor == MapColor.NONE) mapColor = MapColor.STONE;

                int reference = prevColumnHeight != Integer.MIN_VALUE ? prevColumnHeight
                        : (havePrevRow[lx] ? prevRowHeight[lx] : surfaceY);
                MapColor.Brightness brightness;
                if (surfaceY > reference) brightness = MapColor.Brightness.HIGH;
                else if (surfaceY < reference) brightness = MapColor.Brightness.LOW;
                else brightness = MapColor.Brightness.NORMAL;

                colors[lz * SIZE + lx] = mapColor.getPackedId(brightness);
                prevColumnHeight = surfaceY;
                prevRowHeight[lx] = surfaceY;
                havePrevRow[lx] = true;
            }
        }
        return colors;
    }
}
