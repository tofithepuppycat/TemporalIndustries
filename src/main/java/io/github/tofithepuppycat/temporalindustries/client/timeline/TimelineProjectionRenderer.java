package io.github.tofithepuppycat.temporalindustries.client.timeline;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Renders the block-diff preview in-world: a small blinking decal on each face of a block that
 * will change once the player jumps to the currently selected time. Green = added, red = removed,
 * blue = changed. */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class TimelineProjectionRenderer {
    private static final float[] ADD_COLOR = {0.35F, 0.95F, 0.4F};
    private static final float[] REMOVE_COLOR = {0.95F, 0.25F, 0.25F};
    private static final float[] CHANGE_COLOR = {0.35F, 0.55F, 0.95F};
    private static final float MARKER_SIZE = 0.3F;
    private static final float FACE_INSET = 0.002F;
    private static final float BLINK_ALPHA_MIN = 0.25F;
    private static final float BLINK_ALPHA_MAX = 0.85F;
    private static final long BLINK_PERIOD_MS = 900L;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || !TimelineProjectionManager.hasActivePreview()) {
            return;
        }

        TimelineProjectionManager.setCurrentGameTime(level.getGameTime());
        List<TimelineProjectionManager.ProjectionEntry> entries = TimelineProjectionManager.getProjectionEntries(level);
        if (entries.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        double cameraX = camera.getPosition().x;
        double cameraY = camera.getPosition().y;
        double cameraZ = camera.getPosition().z;

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer quadBuffer = bufferSource.getBuffer(RenderType.debugQuads());

        double blinkPhase = (System.currentTimeMillis() % BLINK_PERIOD_MS) / (double) BLINK_PERIOD_MS;
        float alpha = BLINK_ALPHA_MIN + (BLINK_ALPHA_MAX - BLINK_ALPHA_MIN)
                * (0.5F + 0.5F * (float) Math.sin(2.0D * Math.PI * blinkPhase));

        for (TimelineProjectionManager.ProjectionEntry entry : entries) {
            BlockPos pos = entry.getPos();
            double relX = pos.getX() - cameraX;
            double relY = pos.getY() - cameraY;
            double relZ = pos.getZ() - cameraZ;

            float[] color = switch (entry.getType()) {
                case REMOVE -> REMOVE_COLOR;
                case CHANGE -> CHANGE_COLOR;
                default -> ADD_COLOR;
            };
            if (entry.getCurrentState().isAir()) {
                // Nothing there to decal faces onto yet, so float a small cube in the middle instead.
                renderCenterCube(poseStack, quadBuffer, relX, relY, relZ, color[0], color[1], color[2], alpha);
            } else {
                renderFaceMarkers(poseStack, quadBuffer, relX, relY, relZ, color[0], color[1], color[2], alpha);
            }
        }

        bufferSource.endBatch(RenderType.debugQuads());
    }

    /** Draws a small square decal centered on each of the block's 6 faces, just outside the surface. */
    private static void renderFaceMarkers(PoseStack poseStack, VertexConsumer buffer, double relX, double relY, double relZ,
                                          float r, float g, float b, float a) {
        Matrix4f matrix = poseStack.last().pose();
        float x = (float) relX;
        float y = (float) relY;
        float z = (float) relZ;
        float lo = 0.5F - MARKER_SIZE / 2.0F;
        float hi = 0.5F + MARKER_SIZE / 2.0F;

        // -X
        quad(matrix, buffer, r, g, b, a, x - FACE_INSET, y + lo, z + lo, x - FACE_INSET, y + lo, z + hi,
                x - FACE_INSET, y + hi, z + hi, x - FACE_INSET, y + hi, z + lo);
        // +X
        quad(matrix, buffer, r, g, b, a, x + 1 + FACE_INSET, y + lo, z + hi, x + 1 + FACE_INSET, y + lo, z + lo,
                x + 1 + FACE_INSET, y + hi, z + lo, x + 1 + FACE_INSET, y + hi, z + hi);
        // -Y (bottom)
        quad(matrix, buffer, r, g, b, a, x + lo, y - FACE_INSET, z + hi, x + lo, y - FACE_INSET, z + lo,
                x + hi, y - FACE_INSET, z + lo, x + hi, y - FACE_INSET, z + hi);
        // +Y (top)
        quad(matrix, buffer, r, g, b, a, x + lo, y + 1 + FACE_INSET, z + lo, x + lo, y + 1 + FACE_INSET, z + hi,
                x + hi, y + 1 + FACE_INSET, z + hi, x + hi, y + 1 + FACE_INSET, z + lo);
        // -Z
        quad(matrix, buffer, r, g, b, a, x + hi, y + lo, z - FACE_INSET, x + lo, y + lo, z - FACE_INSET,
                x + lo, y + hi, z - FACE_INSET, x + hi, y + hi, z - FACE_INSET);
        // +Z
        quad(matrix, buffer, r, g, b, a, x + lo, y + lo, z + 1 + FACE_INSET, x + hi, y + lo, z + 1 + FACE_INSET,
                x + hi, y + hi, z + 1 + FACE_INSET, x + lo, y + hi, z + 1 + FACE_INSET);
    }

    /** Draws a small cube floating in the middle of the block. */
    private static void renderCenterCube(PoseStack poseStack, VertexConsumer buffer, double relX, double relY, double relZ,
                                         float r, float g, float b, float a) {
        Matrix4f matrix = poseStack.last().pose();
        float x = (float) relX;
        float y = (float) relY;
        float z = (float) relZ;
        float minX = x + 0.5F - MARKER_SIZE / 2.0F, maxX = x + 0.5F + MARKER_SIZE / 2.0F;
        float minY = y + 0.5F - MARKER_SIZE / 2.0F, maxY = y + 0.5F + MARKER_SIZE / 2.0F;
        float minZ = z + 0.5F - MARKER_SIZE / 2.0F, maxZ = z + 0.5F + MARKER_SIZE / 2.0F;

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
