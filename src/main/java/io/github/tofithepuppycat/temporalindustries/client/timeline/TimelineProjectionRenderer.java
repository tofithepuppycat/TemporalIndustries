package io.github.tofithepuppycat.temporalindustries.client.timeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
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

    /** Outer boundary of the previewed claim: a translucent wall standing on each chunk edge that
     * has no other previewed chunk behind it. */
    private static final float[] WALL_COLOR = {0.35F, 0.85F, 1.0F};
    /** Walls are backdrop, not the subject — kept well under the block decals' own blink alpha so
     * they read as an enclosure rather than competing with the changes inside them. */
    private static final float WALL_ALPHA_SCALE = 0.35F;
    /** Half-height of the wall band, centered on the camera — a full world-height curtain would
     * swamp the view, and the player only ever needs to see where the edge is near them. */
    private static final float WALL_HALF_HEIGHT = 8.0F;

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

        renderClaimBoundary(poseStack, quadBuffer, cameraX, cameraZ, alpha * WALL_ALPHA_SCALE);

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

    /**
     * Draws a wall along every chunk edge on the OUTER boundary of the previewed claim — an edge
     * with no other previewed chunk on the far side of it. Interior edges (between two claimed
     * chunks) are skipped, so a multi-chunk Chronosphere claim reads as one enclosure rather than
     * a grid of boxes, and a single-chunk Time Machine still gets a plain box around its chunk.
     * If a single claimed chunk's own tab is open (see
     * {@link TimelineProjectionManager#getSelectedViewChunk()}), that chunk also gets a full box
     * on every side, even the edges it shares with a neighboring claimed chunk — otherwise a
     * chunk in the middle of the claim wouldn't be outlined at all despite being the one whose
     * history the player is actually browsing.
     */
    private static void renderClaimBoundary(PoseStack poseStack, VertexConsumer buffer,
                                            double cameraX, double cameraZ, float alpha) {
        List<ChunkPos> chunks = TimelineProjectionManager.getPreviewChunks();
        if (chunks.isEmpty()) {
            return;
        }

        Set<Long> claimed = new HashSet<>();
        for (ChunkPos chunk : chunks) claimed.add(chunk.toLong());
        ChunkPos selectedChunk = TimelineProjectionManager.getSelectedViewChunk();

        Matrix4f matrix = poseStack.last().pose();
        float r = WALL_COLOR[0], g = WALL_COLOR[1], b = WALL_COLOR[2];
        // The band is camera-relative, so its world height follows the player up and down.
        float yBottom = -WALL_HALF_HEIGHT;
        float yTop = WALL_HALF_HEIGHT;

        for (ChunkPos chunk : chunks) {
            boolean forceAllWalls = chunk.equals(selectedChunk);
            float minX = (float) (chunk.getMinBlockX() - cameraX);
            float maxX = (float) (chunk.getMinBlockX() + 16 - cameraX);
            float minZ = (float) (chunk.getMinBlockZ() - cameraZ);
            float maxZ = (float) (chunk.getMinBlockZ() + 16 - cameraZ);

            if (forceAllWalls || !claimed.contains(new ChunkPos(chunk.x - 1, chunk.z).toLong())) {
                quad(matrix, buffer, r, g, b, alpha, minX, yBottom, minZ, minX, yBottom, maxZ,
                        minX, yTop, maxZ, minX, yTop, minZ);
            }
            if (forceAllWalls || !claimed.contains(new ChunkPos(chunk.x + 1, chunk.z).toLong())) {
                quad(matrix, buffer, r, g, b, alpha, maxX, yBottom, minZ, maxX, yBottom, maxZ,
                        maxX, yTop, maxZ, maxX, yTop, minZ);
            }
            if (forceAllWalls || !claimed.contains(new ChunkPos(chunk.x, chunk.z - 1).toLong())) {
                quad(matrix, buffer, r, g, b, alpha, minX, yBottom, minZ, maxX, yBottom, minZ,
                        maxX, yTop, minZ, minX, yTop, minZ);
            }
            if (forceAllWalls || !claimed.contains(new ChunkPos(chunk.x, chunk.z + 1).toLong())) {
                quad(matrix, buffer, r, g, b, alpha, minX, yBottom, maxZ, maxX, yBottom, maxZ,
                        maxX, yTop, maxZ, minX, yTop, maxZ);
            }
        }
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
