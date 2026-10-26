package io.github.tofithepuppycat.temporalindustries.client.timeline;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders the block-diff preview in-world: a small blinking decal on each face of a block that will
 * change once the player jumps to the currently selected time.
 */
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

    /**
     * Outer boundary of the previewed claim: a translucent wall on each chunk edge with no other
     * previewed chunk behind it.
     */
    private static final float[] WALL_COLOR = {0.35F, 0.85F, 1.0F};
    /**
     * Kept well under the block decals' own blink alpha so walls read as backdrop, not the subject.
     */
    private static final float WALL_ALPHA_SCALE = 0.35F;
    /**
     * Depth-test disabled: a wall sitting exactly on a chunk edge is coplanar with whatever's
     * actually built up to that edge, and no fixed world-space offset reliably avoids z-fighting
     * against it at every distance.
     */
    private static final RenderType WALL_RENDER_TYPE = RenderType.create(
            "temporalindustries_claim_wall",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /**
     * Marker decals' render type: vanilla's {@link RenderType#debugQuads()} without the per-frame
     * depth sort of its translucent quads, which dominates frame time once thousands of decals are
     * on screen.
     */
    private static final RenderType MARKER_RENDER_TYPE = RenderType.create(
            "temporalindustries_marker",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    /** Most marker decals drawn per frame; the rest, being farthest from the camera, are skipped. */
    private static final int MARKER_RENDER_LIMIT = 6000;

    /**
     * Ghost-block mode's render type: a copy of vanilla's {@link RenderType#entityTranslucent}, not
     * the chunk-batching {@link RenderType#translucent()}.
     */
    private static final RenderType GHOST_BLOCK_RENDER_TYPE = RenderType.create(
            "temporalindustries_ghost_block",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            1536,
            true,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                    .createCompositeState(true));

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

        double blinkPhase = (System.currentTimeMillis() % BLINK_PERIOD_MS) / (double) BLINK_PERIOD_MS;
        float alpha = BLINK_ALPHA_MIN + (BLINK_ALPHA_MAX - BLINK_ALPHA_MIN)
                * (0.5F + 0.5F * (float) Math.sin(2.0D * Math.PI * blinkPhase));

        VertexConsumer wallBuffer = bufferSource.getBuffer(WALL_RENDER_TYPE);
        renderClaimBoundary(poseStack, wallBuffer, level, cameraX, cameraY, cameraZ, alpha * WALL_ALPHA_SCALE);
        bufferSource.endBatch(WALL_RENDER_TYPE);

        if (TimelineProjectionManager.isGhostBlockModeEnabled()) {
            renderGhostBlocks(poseStack, bufferSource, entries, cameraX, cameraY, cameraZ, alpha);
        } else {
            renderMarkers(poseStack, bufferSource, entries, cameraX, cameraY, cameraZ, alpha);
        }
    }

    private static void renderMarkers(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                       List<TimelineProjectionManager.ProjectionEntry> entries,
                                       double cameraX, double cameraY, double cameraZ, float alpha) {
        VertexConsumer quadBuffer = bufferSource.getBuffer(MARKER_RENDER_TYPE);

        for (TimelineProjectionManager.ProjectionEntry entry : nearestEntries(entries, cameraX, cameraY, cameraZ, MARKER_RENDER_LIMIT)) {
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
                // Nothing to decal faces onto yet, so float a small cube in the middle instead.
                renderCenterCube(poseStack, quadBuffer, relX, relY, relZ, color[0], color[1], color[2], alpha);
            } else {
                renderFaceMarkers(poseStack, quadBuffer, relX, relY, relZ, color[0], color[1], color[2], alpha);
            }
        }

        bufferSource.endBatch(MARKER_RENDER_TYPE);
    }

    /**
     * Renders real, translucent block models instead of colored decals, capped to the {@link
     * TimelineProjectionManager#GHOST_BLOCK_RENDER_LIMIT} positions nearest the camera — full block
     * models cost far more to draw than the decals' flat quads, so previewing every affected
     * position across a large claim would tank the framerate.
     */
    private static void renderGhostBlocks(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                           List<TimelineProjectionManager.ProjectionEntry> entries,
                                           double cameraX, double cameraY, double cameraZ, float alpha) {
        List<TimelineProjectionManager.ProjectionEntry> nearest =
                nearestEntries(entries, cameraX, cameraY, cameraZ, TimelineProjectionManager.GHOST_BLOCK_RENDER_LIMIT);
        if (nearest.isEmpty()) return;

        MultiBufferSource forcedGhost = renderType -> bufferSource.getBuffer(GHOST_BLOCK_RENDER_TYPE);

        for (TimelineProjectionManager.ProjectionEntry entry : nearest) {
            boolean removal = entry.getType() == TimelineProjectionManager.ProjectionType.REMOVE;
            BlockState renderState = removal ? entry.getCurrentState() : entry.getTargetState();
            if (renderState.isAir()) continue;

            BlockPos pos = entry.getPos();

            RenderSystem.setShaderColor(removal ? 1.0F : 1.0F, removal ? 0.45F : 1.0F, removal ? 0.45F : 1.0F, alpha);
            poseStack.pushPose();
            poseStack.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    renderState, poseStack, forcedGhost, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        bufferSource.endBatch(GHOST_BLOCK_RENDER_TYPE);
    }

    private record RankedEntry(TimelineProjectionManager.ProjectionEntry entry, double distSqr) {}

    /**
     * The limit nearest entries to (cameraX, cameraY, cameraZ), via a bounded max-heap (farthest
     * kept entry at the top, evicted first) so the cost stays O(entries.size() * log(limit))
     * instead of a full sort.
     */
    private static List<TimelineProjectionManager.ProjectionEntry> nearestEntries(
            List<TimelineProjectionManager.ProjectionEntry> entries, double cameraX, double cameraY, double cameraZ, int limit) {
        if (entries.size() <= limit) return entries;

        PriorityQueue<RankedEntry> farthestFirst = new PriorityQueue<>(limit,
                Comparator.comparingDouble(RankedEntry::distSqr).reversed());
        for (TimelineProjectionManager.ProjectionEntry entry : entries) {
            BlockPos pos = entry.getPos();
            double dx = (pos.getX() + 0.5) - cameraX;
            double dy = (pos.getY() + 0.5) - cameraY;
            double dz = (pos.getZ() + 0.5) - cameraZ;
            double distSqr = dx * dx + dy * dy + dz * dz;

            if (farthestFirst.size() < limit) {
                farthestFirst.add(new RankedEntry(entry, distSqr));
            } else if (distSqr < farthestFirst.peek().distSqr()) {
                farthestFirst.poll();
                farthestFirst.add(new RankedEntry(entry, distSqr));
            }
        }

        List<TimelineProjectionManager.ProjectionEntry> result = new java.util.ArrayList<>(farthestFirst.size());
        for (RankedEntry ranked : farthestFirst) result.add(ranked.entry());
        return result;
    }

    /**
     * Draws a wall along every chunk edge on the OUTER boundary of the previewed claim, skipping
     * interior edges so a multi-chunk claim reads as one enclosure.
     */
    private static void renderClaimBoundary(PoseStack poseStack, VertexConsumer buffer, Level level,
                                            double cameraX, double cameraY, double cameraZ, float alpha) {
        List<ChunkPos> chunks = TimelineProjectionManager.getPreviewChunks();
        if (chunks.isEmpty()) {
            return;
        }

        Set<Long> claimed = new HashSet<>();
        for (ChunkPos chunk : chunks) claimed.add(chunk.toLong());

        Matrix4f matrix = poseStack.last().pose();
        float r = WALL_COLOR[0], g = WALL_COLOR[1], b = WALL_COLOR[2];
        // Anchored to the world's build height, not the camera, so the wall stays put as the player flies.
        float yBottom = (float) (level.getMinBuildHeight() - cameraY);
        float yTop = (float) (level.getMaxBuildHeight() - cameraY);

        for (ChunkPos chunk : chunks) {
            float minX = (float) (chunk.getMinBlockX() - cameraX);
            float maxX = (float) (chunk.getMinBlockX() + 16 - cameraX);
            float minZ = (float) (chunk.getMinBlockZ() - cameraZ);
            float maxZ = (float) (chunk.getMinBlockZ() + 16 - cameraZ);

            if (!claimed.contains(new ChunkPos(chunk.x - 1, chunk.z).toLong())) {
                quad(matrix, buffer, r, g, b, alpha, minX, yBottom, minZ, minX, yBottom, maxZ,
                        minX, yTop, maxZ, minX, yTop, minZ);
            }
            if (!claimed.contains(new ChunkPos(chunk.x + 1, chunk.z).toLong())) {
                quad(matrix, buffer, r, g, b, alpha, maxX, yBottom, minZ, maxX, yBottom, maxZ,
                        maxX, yTop, maxZ, maxX, yTop, minZ);
            }
            if (!claimed.contains(new ChunkPos(chunk.x, chunk.z - 1).toLong())) {
                quad(matrix, buffer, r, g, b, alpha, minX, yBottom, minZ, maxX, yBottom, minZ,
                        maxX, yTop, minZ, minX, yTop, minZ);
            }
            if (!claimed.contains(new ChunkPos(chunk.x, chunk.z + 1).toLong())) {
                quad(matrix, buffer, r, g, b, alpha, minX, yBottom, maxZ, maxX, yBottom, maxZ,
                        maxX, yTop, maxZ, minX, yTop, maxZ);
            }
        }
    }

    /**
     * Draws a small square decal centered on each of the block's 6 faces, just outside the surface.
     */
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
