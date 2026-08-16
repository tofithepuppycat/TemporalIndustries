package io.github.tofithepuppycat.temporalindustries.chronomap;

/**
 * Shared circular chunk-area math: which (dx, dz) chunk offsets from an anchor chunk fall within a
 * given radius. Used by the Chronosphere's claim bounds, the shared chunk-selection map grid (see
 * {@link io.github.tofithepuppycat.temporalindustries.client.chunkmap.ChunkSelectionGrid}), and the
 * chunk-thumbnail request handlers' server-side sampling/bounds checks, so the claimable shape, the
 * drawn/clickable grid, and the terrain actually sampled always agree.
 */
public final class ChunkArea {
    private ChunkArea() {}

    public static boolean isWithinRadius(int radius, int dx, int dz) {
        return dx * dx + dz * dz <= radius * radius;
    }
}
