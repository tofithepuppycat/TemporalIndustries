package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;

/** Draws linked inputs in red and outputs in blue in the world; used for the wrench's selection. */
public class EntropicPylonMarkRenderer {
    private static final float[] INPUT_COLOR = {1.0F, 0.25F, 0.25F};
    private static final float[] OUTPUT_COLOR = {0.35F, 0.55F, 1.0F};
    private static final float FILL_ALPHA = 0.15F;
    private static final float OUTLINE_ALPHA = 0.9F;
    private static final float OUTLINE_THICKNESS = 0.03F;
    private static final double LINK_HALF_WIDTH = 0.03D;
    private static final float LINK_ALPHA = 0.9F;

    /** Draws a red outline for each input and a blue one for each output. */
    static void renderMarks(RenderLevelStageEvent event, Minecraft minecraft, List<BlockPos> inputs, List<BlockPos> outputs) {
        renderMarks(event, minecraft, null, inputs, outputs);
    }

    /**
     * Draws a red outline for each input and a blue one for each output, plus a line in the same
     * colour from the centre of {@code origin} to each of them when {@code origin} is given.
     */
    static void renderMarks(RenderLevelStageEvent event, Minecraft minecraft, @Nullable BlockPos origin,
                            List<BlockPos> inputs, List<BlockPos> outputs) {
        if (inputs.isEmpty() && outputs.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer quadBuffer = bufferSource.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = poseStack.last().pose();

        for (BlockPos pos : inputs) {
            renderMark(matrix, quadBuffer, pos, camX, camY, camZ, INPUT_COLOR);
        }
        for (BlockPos pos : outputs) {
            renderMark(matrix, quadBuffer, pos, camX, camY, camZ, OUTPUT_COLOR);
        }
        if (origin != null) {
            for (BlockPos pos : inputs) {
                renderLink(matrix, quadBuffer, origin, pos, camX, camY, camZ, INPUT_COLOR);
            }
            for (BlockPos pos : outputs) {
                renderLink(matrix, quadBuffer, origin, pos, camX, camY, camZ, OUTPUT_COLOR);
            }
        }

        bufferSource.endBatch(RenderType.debugQuads());
    }

    /** A flat ribbon between two block centres that always turns its face towards the camera. */
    private static void renderLink(Matrix4f matrix, VertexConsumer buffer, BlockPos from, BlockPos to,
                                   double camX, double camY, double camZ, float[] color) {
        double ax = from.getX() + 0.5D - camX, ay = from.getY() + 0.5D - camY, az = from.getZ() + 0.5D - camZ;
        double bx = to.getX() + 0.5D - camX, by = to.getY() + 0.5D - camY, bz = to.getZ() + 0.5D - camZ;
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        // The ribbon's width direction is perpendicular to both the line and the view of its midpoint.
        double vx = (ax + bx) / 2, vy = (ay + by) / 2, vz = (az + bz) / 2;
        double sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;
        double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (length < 1.0E-6D) {
            return;
        }
        double scale = LINK_HALF_WIDTH / length;
        sx *= scale;
        sy *= scale;
        sz *= scale;

        float r = color[0], g = color[1], b = color[2];
        buffer.addVertex(matrix, (float) (ax - sx), (float) (ay - sy), (float) (az - sz)).setColor(r, g, b, LINK_ALPHA);
        buffer.addVertex(matrix, (float) (ax + sx), (float) (ay + sy), (float) (az + sz)).setColor(r, g, b, LINK_ALPHA);
        buffer.addVertex(matrix, (float) (bx + sx), (float) (by + sy), (float) (bz + sz)).setColor(r, g, b, LINK_ALPHA);
        buffer.addVertex(matrix, (float) (bx - sx), (float) (by - sy), (float) (bz - sz)).setColor(r, g, b, LINK_ALPHA);
    }

    private static void renderMark(Matrix4f matrix, VertexConsumer buffer, BlockPos pos,
                                    double camX, double camY, double camZ, float[] color) {
        BoxOutlineRenderer.renderBox(matrix, buffer,
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D,
                camX, camY, camZ, color, FILL_ALPHA, color, OUTLINE_ALPHA, OUTLINE_THICKNESS);
    }
}
