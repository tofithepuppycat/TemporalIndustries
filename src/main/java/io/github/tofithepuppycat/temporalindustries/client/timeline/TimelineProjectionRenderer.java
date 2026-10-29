package io.github.tofithepuppycat.temporalindustries.client.timeline;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

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
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
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
        renderClaimBoundary(poseStack, wallBuffer, level, TimelineProjectionManager.getPreviewChunks(), WALL_COLOR,
                cameraX, cameraY, cameraZ, alpha * WALL_ALPHA_SCALE);
        bufferSource.endBatch(WALL_RENDER_TYPE);

        if (TimelineProjectionManager.isGhostBlockModeEnabled()) {
            // Ghost models show what will appear; a removal has no target model, so it keeps its marker.
            splitByRemoval(entries);
            renderGhostBlocks(poseStack, bufferSource, level, additionsAndChanges, cameraX, cameraY, cameraZ, alpha);
            renderMarkers(poseStack, bufferSource, level, removals, cameraX, cameraY, cameraZ, alpha);
        } else {
            renderMarkers(poseStack, bufferSource, level, entries, cameraX, cameraY, cameraZ, alpha);
        }
    }

    /** While the Chrono Remote Control previews its chunk selection: the claimed area's outer boundary. */
    @SubscribeEvent
    public static void onRenderChunkSelection(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || !io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState.isChunkPreview()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }

        Set<Long> selected = io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState.getSelectedChunks();
        List<ChunkPos> claimed = new java.util.ArrayList<>(selected.size());
        for (long key : selected) claimed.add(new ChunkPos(key));

        Camera camera = minecraft.gameRenderer.getMainCamera();
        double cameraX = camera.getPosition().x;
        double cameraY = camera.getPosition().y;
        double cameraZ = camera.getPosition().z;

        double blinkPhase = (System.currentTimeMillis() % BLINK_PERIOD_MS) / (double) BLINK_PERIOD_MS;
        float alpha = (BLINK_ALPHA_MIN + (BLINK_ALPHA_MAX - BLINK_ALPHA_MIN)
                * (0.5F + 0.5F * (float) Math.sin(2.0D * Math.PI * blinkPhase))) * WALL_ALPHA_SCALE;

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer wallBuffer = bufferSource.getBuffer(WALL_RENDER_TYPE);
        renderClaimBoundary(event.getPoseStack(), wallBuffer, level, claimed, WALL_COLOR, cameraX, cameraY, cameraZ, alpha);
        bufferSource.endBatch(WALL_RENDER_TYPE);
    }

    private static void renderMarkers(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                       Level level, List<TimelineProjectionManager.ProjectionEntry> entries,
                                       double cameraX, double cameraY, double cameraZ, float alpha) {
        VertexConsumer quadBuffer = bufferSource.getBuffer(MARKER_RENDER_TYPE);

        for (TimelineProjectionManager.ProjectionEntry entry : cachedNearest(MARKER_CACHE, entries, cameraX, cameraY, cameraZ, MARKER_RENDER_LIMIT)) {
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
                renderFaceMarkers(poseStack, quadBuffer, level, pos, relX, relY, relZ, color[0], color[1], color[2], alpha);
            }
        }

        bufferSource.endBatch(MARKER_RENDER_TYPE);
    }

    private static List<TimelineProjectionManager.ProjectionEntry> splitSource;
    private static List<TimelineProjectionManager.ProjectionEntry> additionsAndChanges = List.of();
    private static List<TimelineProjectionManager.ProjectionEntry> removals = List.of();

    /** Splits entries into {@link #additionsAndChanges} and {@link #removals}, once per distinct list. */
    private static void splitByRemoval(List<TimelineProjectionManager.ProjectionEntry> entries) {
        if (splitSource == entries) return;

        List<TimelineProjectionManager.ProjectionEntry> kept = new java.util.ArrayList<>();
        List<TimelineProjectionManager.ProjectionEntry> removed = new java.util.ArrayList<>();
        for (TimelineProjectionManager.ProjectionEntry entry : entries) {
            (entry.getType() == TimelineProjectionManager.ProjectionType.REMOVE ? removed : kept).add(entry);
        }
        additionsAndChanges = kept;
        removals = removed;
        splitSource = entries;
    }

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final RandomSource GHOST_RANDOM = RandomSource.create();

    /**
     * Renders real, translucent block models instead of colored decals, capped to the {@link
     * TimelineProjectionManager#GHOST_BLOCK_RENDER_LIMIT} positions nearest the camera — full block
     * models cost far more to draw than the decals' flat quads, so previewing every affected
     * position across a large claim would tank the framerate. Only faces the camera can see are
     * drawn.
     */
    private static void renderGhostBlocks(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, Level level,
                                           List<TimelineProjectionManager.ProjectionEntry> entries,
                                           double cameraX, double cameraY, double cameraZ, float alpha) {
        List<TimelineProjectionManager.ProjectionEntry> nearest =
                cachedNearest(GHOST_CACHE, entries, cameraX, cameraY, cameraZ, TimelineProjectionManager.GHOST_BLOCK_RENDER_LIMIT);
        if (nearest.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        VertexConsumer ghostBuffer = bufferSource.getBuffer(GHOST_BLOCK_RENDER_TYPE);
        MultiBufferSource forcedGhost = renderType -> ghostBuffer;

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        for (TimelineProjectionManager.ProjectionEntry entry : nearest) {
            BlockState renderState = entry.getTargetState();
            if (renderState.isAir()) continue;

            BlockPos pos = entry.getPos();
            double relX = pos.getX() - cameraX;
            double relY = pos.getY() - cameraY;
            double relZ = pos.getZ() - cameraZ;

            poseStack.pushPose();
            poseStack.translate(relX, relY, relZ);
            if (renderState.getRenderShape() == RenderShape.MODEL) {
                renderVisibleFaces(poseStack, ghostBuffer, level, pos, renderState, (float) relX, (float) relY, (float) relZ);
            } else {
                minecraft.getBlockRenderer().renderSingleBlock(
                        renderState, poseStack, forcedGhost, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
            poseStack.popPose();
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        bufferSource.endBatch(GHOST_BLOCK_RENDER_TYPE);
    }

    /**
     * Submits the quads of state's model that the camera can see: each side's quads only while that
     * side faces the camera and is not buried against an opaque block, plus the model's interior
     * quads, which have no side to test.
     */
    private static void renderVisibleFaces(PoseStack poseStack, VertexConsumer buffer, Level level, BlockPos pos,
                                           BlockState state, float relX, float relY, float relZ) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
        BlockColors blockColors = minecraft.getBlockColors();
        PoseStack.Pose pose = poseStack.last();
        long seed = state.getSeed(pos);

        for (Direction direction : DIRECTIONS) {
            float rel = switch (direction.getAxis()) {
                case X -> relX;
                case Y -> relY;
                case Z -> relZ;
            };
            float plane = rel + (direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F);
            if (!faceVisible(level, pos, direction, plane)) continue;

            GHOST_RANDOM.setSeed(seed);
            putQuads(pose, buffer, model.getQuads(state, direction, GHOST_RANDOM, ModelData.EMPTY, null), blockColors, state, level, pos);
        }

        GHOST_RANDOM.setSeed(seed);
        putQuads(pose, buffer, model.getQuads(state, null, GHOST_RANDOM, ModelData.EMPTY, null), blockColors, state, level, pos);
    }

    private static void putQuads(PoseStack.Pose pose, VertexConsumer buffer, List<BakedQuad> quads, BlockColors blockColors,
                                 BlockState state, Level level, BlockPos pos) {
        for (BakedQuad quad : quads) {
            float r = 1.0F, g = 1.0F, b = 1.0F;
            if (quad.isTinted()) {
                int tint = blockColors.getColor(state, level, pos, quad.getTintIndex());
                r = (tint >> 16 & 0xFF) / 255.0F;
                g = (tint >> 8 & 0xFF) / 255.0F;
                b = (tint & 0xFF) / 255.0F;
            }
            buffer.putBulkData(pose, quad, r, g, b, 1.0F, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
    }

    private record RankedEntry(TimelineProjectionManager.ProjectionEntry entry, double distSqr) {}

    /** The last nearest-entries selection, reused while the camera stays within a few blocks of where it was made. */
    private static final class NearestCache {
        List<TimelineProjectionManager.ProjectionEntry> source;
        int limit;
        double x, y, z;
        List<TimelineProjectionManager.ProjectionEntry> result;
    }

    private static final double NEAREST_REUSE_DISTANCE_SQR = 4.0 * 4.0;
    private static final NearestCache MARKER_CACHE = new NearestCache();
    private static final NearestCache GHOST_CACHE = new NearestCache();

    private static List<TimelineProjectionManager.ProjectionEntry> cachedNearest(
            NearestCache cache, List<TimelineProjectionManager.ProjectionEntry> entries,
            double cameraX, double cameraY, double cameraZ, int limit) {
        if (entries.size() <= limit) return entries;

        double dx = cameraX - cache.x, dy = cameraY - cache.y, dz = cameraZ - cache.z;
        if (cache.source == entries && cache.limit == limit
                && dx * dx + dy * dy + dz * dz < NEAREST_REUSE_DISTANCE_SQR) {
            return cache.result;
        }
        cache.result = nearestEntries(entries, cameraX, cameraY, cameraZ, limit);
        cache.source = entries;
        cache.limit = limit;
        cache.x = cameraX;
        cache.y = cameraY;
        cache.z = cameraZ;
        return cache.result;
    }

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
                                            List<ChunkPos> chunks, float[] color,
                                            double cameraX, double cameraY, double cameraZ, float alpha) {
        if (chunks.isEmpty()) {
            return;
        }

        int[] edges = boundaryEdges(chunks);

        Matrix4f matrix = poseStack.last().pose();
        float r = color[0], g = color[1], b = color[2];
        // Anchored to the world's build height, not the camera, so the wall stays put as the player flies.
        float yBottom = (float) (level.getMinBuildHeight() - cameraY);
        float yTop = (float) (level.getMaxBuildHeight() - cameraY);

        for (int i = 0; i < edges.length; i += 3) {
            float minX = (float) (edges[i] * 16 - cameraX);
            float maxX = (float) (edges[i] * 16 + 16 - cameraX);
            float minZ = (float) (edges[i + 1] * 16 - cameraZ);
            float maxZ = (float) (edges[i + 1] * 16 + 16 - cameraZ);

            switch (edges[i + 2]) {
                case EDGE_WEST -> quad(matrix, buffer, r, g, b, alpha, minX, yBottom, minZ, minX, yBottom, maxZ,
                        minX, yTop, maxZ, minX, yTop, minZ);
                case EDGE_EAST -> quad(matrix, buffer, r, g, b, alpha, maxX, yBottom, minZ, maxX, yBottom, maxZ,
                        maxX, yTop, maxZ, maxX, yTop, minZ);
                case EDGE_NORTH -> quad(matrix, buffer, r, g, b, alpha, minX, yBottom, minZ, maxX, yBottom, minZ,
                        maxX, yTop, minZ, minX, yTop, minZ);
                default -> quad(matrix, buffer, r, g, b, alpha, minX, yBottom, maxZ, maxX, yBottom, maxZ,
                        maxX, yTop, maxZ, minX, yTop, maxZ);
            }
        }
    }

    private static final int EDGE_WEST = 0;
    private static final int EDGE_EAST = 1;
    private static final int EDGE_NORTH = 2;
    private static final int EDGE_SOUTH = 3;

    private static int boundaryKey = 0;
    private static int[] boundaryEdges = new int[0];

    /**
     * The outer boundary of chunks as (chunkX, chunkZ, side) triples, recomputed only when the set
     * of chunks changes rather than on every frame.
     */
    private static int[] boundaryEdges(List<ChunkPos> chunks) {
        int key = chunks.size();
        for (ChunkPos chunk : chunks) key = key * 31 + Long.hashCode(chunk.toLong());
        if (key == boundaryKey && boundaryEdges.length > 0) return boundaryEdges;

        Set<Long> claimed = new HashSet<>();
        for (ChunkPos chunk : chunks) claimed.add(chunk.toLong());

        IntArrayList edges = new IntArrayList();
        for (ChunkPos chunk : chunks) {
            addEdgeIf(edges, claimed, chunk, chunk.x - 1, chunk.z, EDGE_WEST);
            addEdgeIf(edges, claimed, chunk, chunk.x + 1, chunk.z, EDGE_EAST);
            addEdgeIf(edges, claimed, chunk, chunk.x, chunk.z - 1, EDGE_NORTH);
            addEdgeIf(edges, claimed, chunk, chunk.x, chunk.z + 1, EDGE_SOUTH);
        }
        boundaryKey = key;
        boundaryEdges = edges.toIntArray();
        return boundaryEdges;
    }

    private static void addEdgeIf(IntArrayList edges, Set<Long> claimed, ChunkPos chunk, int neighbourX, int neighbourZ, int side) {
        if (claimed.contains(ChunkPos.asLong(neighbourX, neighbourZ))) return;
        edges.add(chunk.x);
        edges.add(chunk.z);
        edges.add(side);
    }

    private static final BlockPos.MutableBlockPos NEIGHBOUR = new BlockPos.MutableBlockPos();

    /**
     * Whether a face is worth drawing: it must face the camera and not sit against an opaque
     * neighbour, which would hide it anyway. {@code planeOffset} is the face's coordinate relative to
     * the camera along its axis.
     */
    private static boolean faceVisible(Level level, BlockPos pos, Direction direction, float planeOffset) {
        boolean facesCamera = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? planeOffset < 0.0F : planeOffset > 0.0F;
        if (!facesCamera) return false;
        NEIGHBOUR.setWithOffset(pos, direction);
        return !level.getBlockState(NEIGHBOUR).isSolidRender(level, NEIGHBOUR);
    }

    /**
     * Draws a small square decal centered on each of the block's faces that the camera can see,
     * just outside the surface.
     */
    private static void renderFaceMarkers(PoseStack poseStack, VertexConsumer buffer, Level level, BlockPos pos,
                                          double relX, double relY, double relZ,
                                          float r, float g, float b, float a) {
        Matrix4f matrix = poseStack.last().pose();
        float x = (float) relX;
        float y = (float) relY;
        float z = (float) relZ;
        float lo = 0.5F - MARKER_SIZE / 2.0F;
        float hi = 0.5F + MARKER_SIZE / 2.0F;

        if (faceVisible(level, pos, Direction.WEST, x)) {
            quad(matrix, buffer, r, g, b, a, x - FACE_INSET, y + lo, z + lo, x - FACE_INSET, y + lo, z + hi,
                    x - FACE_INSET, y + hi, z + hi, x - FACE_INSET, y + hi, z + lo);
        }
        if (faceVisible(level, pos, Direction.EAST, x + 1)) {
            quad(matrix, buffer, r, g, b, a, x + 1 + FACE_INSET, y + lo, z + hi, x + 1 + FACE_INSET, y + lo, z + lo,
                    x + 1 + FACE_INSET, y + hi, z + lo, x + 1 + FACE_INSET, y + hi, z + hi);
        }
        if (faceVisible(level, pos, Direction.DOWN, y)) {
            quad(matrix, buffer, r, g, b, a, x + lo, y - FACE_INSET, z + hi, x + lo, y - FACE_INSET, z + lo,
                    x + hi, y - FACE_INSET, z + lo, x + hi, y - FACE_INSET, z + hi);
        }
        if (faceVisible(level, pos, Direction.UP, y + 1)) {
            quad(matrix, buffer, r, g, b, a, x + lo, y + 1 + FACE_INSET, z + lo, x + lo, y + 1 + FACE_INSET, z + hi,
                    x + hi, y + 1 + FACE_INSET, z + hi, x + hi, y + 1 + FACE_INSET, z + lo);
        }
        if (faceVisible(level, pos, Direction.NORTH, z)) {
            quad(matrix, buffer, r, g, b, a, x + hi, y + lo, z - FACE_INSET, x + lo, y + lo, z - FACE_INSET,
                    x + lo, y + hi, z - FACE_INSET, x + hi, y + hi, z - FACE_INSET);
        }
        if (faceVisible(level, pos, Direction.SOUTH, z + 1)) {
            quad(matrix, buffer, r, g, b, a, x + lo, y + lo, z + 1 + FACE_INSET, x + hi, y + lo, z + 1 + FACE_INSET,
                    x + hi, y + hi, z + 1 + FACE_INSET, x + lo, y + hi, z + 1 + FACE_INSET);
        }
    }

    /** Draws the faces of a small cube floating in the middle of the block that point at the camera. */
    private static void renderCenterCube(PoseStack poseStack, VertexConsumer buffer, double relX, double relY, double relZ,
                                         float r, float g, float b, float a) {
        Matrix4f matrix = poseStack.last().pose();
        float x = (float) relX;
        float y = (float) relY;
        float z = (float) relZ;
        float minX = x + 0.5F - MARKER_SIZE / 2.0F, maxX = x + 0.5F + MARKER_SIZE / 2.0F;
        float minY = y + 0.5F - MARKER_SIZE / 2.0F, maxY = y + 0.5F + MARKER_SIZE / 2.0F;
        float minZ = z + 0.5F - MARKER_SIZE / 2.0F, maxZ = z + 0.5F + MARKER_SIZE / 2.0F;

        // Coordinates are camera-relative, so a face points at the camera when its plane lies on the
        // camera's side of the origin.
        if (minX > 0) quad(matrix, buffer, r, g, b, a, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ);
        if (maxX < 0) quad(matrix, buffer, r, g, b, a, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ);
        if (minY > 0) quad(matrix, buffer, r, g, b, a, minX, minY, maxZ, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ);
        if (maxY < 0) quad(matrix, buffer, r, g, b, a, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        if (minZ > 0) quad(matrix, buffer, r, g, b, a, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ);
        if (maxZ < 0) quad(matrix, buffer, r, g, b, a, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ);
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
