package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/** Shared debug-quad box/outline drawing for in-world overlay renderers: a translucent fill plus a
 * thin edged outline for a camera-relative axis-aligned region. */
public final class BoxOutlineRenderer {
    /** Grows every rendered box outward by this much on every axis to avoid z-fighting with block faces. */
    public static final float SURFACE_OUTSET = 0.004F;

    private BoxOutlineRenderer() {}

    public static void renderBox(Matrix4f matrix, VertexConsumer buffer,
                                  double regionMinX, double regionMinY, double regionMinZ,
                                  double regionMaxX, double regionMaxY, double regionMaxZ,
                                  double camX, double camY, double camZ,
                                  float[] fillColor, float fillAlpha, float[] outlineColor, float outlineAlpha,
                                  float thickness) {
        float minX = (float) (regionMinX - SURFACE_OUTSET - camX);
        float minY = (float) (regionMinY - SURFACE_OUTSET - camY);
        float minZ = (float) (regionMinZ - SURFACE_OUTSET - camZ);
        float maxX = (float) (regionMaxX + SURFACE_OUTSET - camX);
        float maxY = (float) (regionMaxY + SURFACE_OUTSET - camY);
        float maxZ = (float) (regionMaxZ + SURFACE_OUTSET - camZ);

        box(matrix, buffer, minX, minY, minZ, maxX, maxY, maxZ, fillColor[0], fillColor[1], fillColor[2], fillAlpha);
        outline(matrix, buffer, minX, minY, minZ, maxX, maxY, maxZ, thickness,
                outlineColor[0], outlineColor[1], outlineColor[2], outlineAlpha);
    }

    /** Draws a box's 12 edges as thin boxes (rather than GL lines) so they have real thickness. */
    private static void outline(Matrix4f matrix, VertexConsumer buffer, float minX, float minY, float minZ,
                                 float maxX, float maxY, float maxZ, float thickness,
                                 float r, float g, float b, float a) {
        for (int xi = 0; xi < 2; xi++) {
            for (int zi = 0; zi < 2; zi++) {
                float x = xi == 0 ? minX : maxX;
                float z = zi == 0 ? minZ : maxZ;
                edgeBox(matrix, buffer, x, minY, z, x, maxY, z, thickness, r, g, b, a);
            }
        }
        for (int yi = 0; yi < 2; yi++) {
            for (int zi = 0; zi < 2; zi++) {
                float y = yi == 0 ? minY : maxY;
                float z = zi == 0 ? minZ : maxZ;
                edgeBox(matrix, buffer, minX, y, z, maxX, y, z, thickness, r, g, b, a);
            }
        }
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                float x = xi == 0 ? minX : maxX;
                float y = yi == 0 ? minY : maxY;
                edgeBox(matrix, buffer, x, y, minZ, x, y, maxZ, thickness, r, g, b, a);
            }
        }
    }

    private static void edgeBox(Matrix4f matrix, VertexConsumer buffer, float x1, float y1, float z1,
                                 float x2, float y2, float z2, float thickness, float r, float g, float b, float a) {
        float t = thickness / 2.0F;
        float minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        float minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        if (minX == maxX) { minX -= t; maxX += t; }
        if (minY == maxY) { minY -= t; maxY += t; }
        if (minZ == maxZ) { minZ -= t; maxZ += t; }
        box(matrix, buffer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }

    /** Draws all 6 faces of an axis-aligned box. */
    private static void box(Matrix4f matrix, VertexConsumer buffer, float minX, float minY, float minZ,
                             float maxX, float maxY, float maxZ, float r, float g, float b, float a) {
        quad(matrix, buffer, r, g, b, a, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ);
        quad(matrix, buffer, r, g, b, a, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ);
        quad(matrix, buffer, r, g, b, a, minX, minY, maxZ, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ);
        quad(matrix, buffer, r, g, b, a, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        quad(matrix, buffer, r, g, b, a, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ);
        quad(matrix, buffer, r, g, b, a, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ);
    }

    private static void quad(Matrix4f matrix, VertexConsumer buffer, float r, float g, float b, float a,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4) {
        buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a);
        buffer.addVertex(matrix, x4, y4, z4).setColor(r, g, b, a);
    }
}
