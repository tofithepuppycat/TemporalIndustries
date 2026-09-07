package io.github.tofithepuppycat.temporalindustries.chronomap;

/** Shared chunk-area math: which (dx, dz) chunk offsets from an anchor chunk fall within a given
 * radius, under either a circular or square {@link Shape}. Keeps claim bounds, the selection grid,
 * and terrain sampling all agreeing on the same shape. */
public final class ChunkArea {
    private ChunkArea() {}

    /** The outline a radius describes: an inscribed circle, or the full (2r+1)x(2r+1) box. */
    public enum Shape {
        CIRCLE {
            @Override
            public boolean contains(int radius, int dx, int dz) {
                return dx * dx + dz * dz <= radius * radius;
            }
        },
        SQUARE {
            @Override
            public boolean contains(int radius, int dx, int dz) {
                return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
            }
        };

        public abstract boolean contains(int radius, int dx, int dz);
    }

    public static boolean isWithinRadius(int radius, int dx, int dz) {
        return Shape.CIRCLE.contains(radius, dx, dz);
    }
}
