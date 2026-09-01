package io.github.tofithepuppycat.temporalindustries.block;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Points spaced along the 12 edges of an axis-aligned box, in absolute world coordinates - used to
 * draw a particle outline around a structure (a single missing frame position, or an entire formed
 * multiblock's bounding box) rather than a burst at its center. */
public final class BoxEdgeParticles {
    private static final int[][] EDGES = {
            {0, 1}, {1, 5}, {5, 3}, {3, 0}, // bottom face
            {2, 4}, {4, 7}, {7, 6}, {6, 2}, // top face
            {0, 2}, {1, 4}, {3, 6}, {5, 7}  // verticals
    };

    private BoxEdgeParticles() {}

    /** @param spacing target distance between consecutive points along each edge, in blocks - edges
     * are always given at least their two endpoints, even if shorter than one spacing. */
    public static List<Vector3f> outline(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double spacing) {
        float[][] corners = {
                {(float) minX, (float) minY, (float) minZ}, {(float) maxX, (float) minY, (float) minZ},
                {(float) minX, (float) maxY, (float) minZ}, {(float) minX, (float) minY, (float) maxZ},
                {(float) maxX, (float) maxY, (float) minZ}, {(float) maxX, (float) minY, (float) maxZ},
                {(float) minX, (float) maxY, (float) maxZ}, {(float) maxX, (float) maxY, (float) maxZ}
        };

        List<Vector3f> points = new ArrayList<>();
        for (int[] edge : EDGES) {
            float[] a = corners[edge[0]];
            float[] b = corners[edge[1]];
            double length = Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
            int steps = Math.max(1, (int) Math.round(length / spacing));
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                points.add(new Vector3f(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t));
            }
        }
        return points;
    }

    private static double sq(double v) {
        return v * v;
    }
}
