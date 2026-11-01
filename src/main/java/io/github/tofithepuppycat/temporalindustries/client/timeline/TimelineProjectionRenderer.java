package io.github.tofithepuppycat.temporalindustries.client.timeline;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import org.jetbrains.annotations.Nullable;
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

    /** Most marker decals baked into the marker mesh; the rest, being farthest from the camera, are skipped. */
    private static final int MARKER_RENDER_LIMIT = 50_000;

    /**
     * Pulls ghost faces only a little toward the camera, enough to win against the block they
     * overlap without drawing in front of its neighbours; vanilla's layering offsets by far more.
     */
    private static final RenderStateShard.LayeringStateShard GHOST_LAYERING = new RenderStateShard.LayeringStateShard(
            "temporalindustries_ghost_layering",
            () -> {
                RenderSystem.enablePolygonOffset();
                RenderSystem.polygonOffset(-0.5F, -1.0F);
            },
            () -> {
                RenderSystem.polygonOffset(0.0F, 0.0F);
                RenderSystem.disablePolygonOffset();
            });

    /**
     * Ghost-block mode's render type: the block shader and format of vanilla's {@link
     * RenderType#translucent()}, so a quad's vertex colors and light are all that shade it, but
     * drawn immediately instead of batched into chunk meshes.
     */
    private static final RenderType GHOST_BLOCK_RENDER_TYPE = RenderType.create(
            "temporalindustries_ghost_block",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            1536,
            true,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .setLayeringState(GHOST_LAYERING)
                    .createCompositeState(true));

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || !TimelineProjectionManager.hasActivePreview()) {
            closeMeshes();
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

        if (TimelineProjectionManager.isGhostBlockModeEnabled()) {
            // Ghost models show what will appear; a removal has no target model, so it keeps its marker.
            splitByRemoval(entries);
            renderGhostBlocks(poseStack, bufferSource, level, additionsAndChanges,
                    event.getModelViewMatrix(), event.getProjectionMatrix(), cameraX, cameraY, cameraZ);
            renderMarkers(level, removals, event.getModelViewMatrix(), event.getProjectionMatrix(), cameraX, cameraY, cameraZ, alpha);
        } else {
            GHOST_MESH.close();
            ghostEntityBlocks.clear();
            renderMarkers(level, entries, event.getModelViewMatrix(), event.getProjectionMatrix(), cameraX, cameraY, cameraZ, alpha);
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

    /**
     * Draws a blinking decal on each exposed face of the nearest {@link #MARKER_RENDER_LIMIT}
     * entries, as one cached vertex buffer like the ghost blocks; the blink is the draw's color
     * modulation, so the buffer itself never changes with it.
     */
    private static void renderMarkers(Level level, List<TimelineProjectionManager.ProjectionEntry> entries,
                                       Matrix4f modelView, Matrix4f projection,
                                       double cameraX, double cameraY, double cameraZ, float alpha) {
        List<TimelineProjectionManager.ProjectionEntry> nearest = cachedNearest(MARKER_CACHE, entries,
                cameraX, cameraY, cameraZ, MARKER_RENDER_LIMIT, MESH_REUSE_DISTANCE_SQR);
        MARKER_MESH.ensure(level, nearest, cameraX, cameraY, cameraZ, DefaultVertexFormat.POSITION_COLOR,
                TimelineProjectionRenderer::markerSignature, (builder, origin) -> buildMarkerMesh(level, nearest, builder, origin));

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        MARKER_MESH.draw(MARKER_RENDER_TYPE, modelView, projection, cameraX, cameraY, cameraZ);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static long markerSignature(List<TimelineProjectionManager.ProjectionEntry> entries) {
        long hash = entries.size();
        for (TimelineProjectionManager.ProjectionEntry entry : entries) {
            hash = hash * 31L + entry.getPos().asLong();
            hash = hash * 31L + entry.getType().ordinal();
            hash = hash * 31L + Block.getId(entry.getCurrentState());
        }
        return hash;
    }

    private static void buildMarkerMesh(Level level, List<TimelineProjectionManager.ProjectionEntry> selected,
                                        BufferBuilder builder, BlockPos origin) {
        Matrix4f identity = new Matrix4f();
        for (TimelineProjectionManager.ProjectionEntry entry : selected) {
            BlockPos pos = entry.getPos();
            float x = pos.getX() - origin.getX();
            float y = pos.getY() - origin.getY();
            float z = pos.getZ() - origin.getZ();

            float[] color = switch (entry.getType()) {
                case REMOVE -> REMOVE_COLOR;
                case CHANGE -> CHANGE_COLOR;
                default -> ADD_COLOR;
            };
            if (entry.getCurrentState().isAir()) {
                // Nothing to decal faces onto yet, so float a small cube in the middle instead.
                putCenterCube(identity, builder, x, y, z, color[0], color[1], color[2]);
            } else {
                putFaceMarkers(identity, builder, level, pos, x, y, z, color[0], color[1], color[2]);
            }
        }
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
    private static final BlockPos.MutableBlockPos LIGHT_POS = new BlockPos.MutableBlockPos();
    private static final RandomSource GHOST_RANDOM = RandomSource.create();

    /** Selections are rebuilt around the camera once it has moved this far from where they were made. */
    private static final double MESH_REUSE_DISTANCE_SQR = 16.0 * 16.0;
    private static final long MESH_MAX_AGE_TICKS = 400L;

    private static final CachedMesh GHOST_MESH = new CachedMesh();
    private static final CachedMesh MARKER_MESH = new CachedMesh();
    /** Previewed blocks that only their item renderer can draw, which cannot go into the mesh. */
    private static final List<TimelineProjectionManager.ProjectionEntry> ghostEntityBlocks = new java.util.ArrayList<>();
    private static final NearestCache GHOST_MESH_SELECTION = new NearestCache();

    /** A vertex buffer baked from a selection of entries, kept until the selection or the camera's place in it changes. */
    private static final class CachedMesh {
        @Nullable private VertexBuffer buffer;
        private BlockPos origin = BlockPos.ZERO;
        @Nullable private List<TimelineProjectionManager.ProjectionEntry> source;
        @Nullable private Level level;
        private long signature;
        private long builtTick;

        /** Fills the builder with quads positioned relative to origin. */
        interface Filler {
            void fill(BufferBuilder builder, BlockPos origin);
        }

        /**
         * Rebuilds the mesh with filler unless selected is what it was built from, or only differs
         * in identity and not in content (judged by signature) and is not stale.
         */
        void ensure(Level level, List<TimelineProjectionManager.ProjectionEntry> selected, double cameraX, double cameraY,
                    double cameraZ, VertexFormat format, java.util.function.ToLongFunction<List<TimelineProjectionManager.ProjectionEntry>> signer,
                    Filler filler) {
            if (source == selected && this.level == level) return;

            long now = level.getGameTime();
            long newSignature = signer.applyAsLong(selected);
            if (this.level == level && newSignature == signature && now >= builtTick && now - builtTick < MESH_MAX_AGE_TICKS) {
                source = selected;
                return;
            }

            close();
            this.level = level;
            source = selected;
            signature = newSignature;
            builtTick = now;
            origin = BlockPos.containing(cameraX, cameraY, cameraZ);

            try (ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20)) {
                BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
                filler.fill(builder, origin);
                MeshData mesh = builder.build();
                if (mesh == null) return;
                buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(mesh);
                VertexBuffer.unbind();
            }
        }

        void draw(RenderType type, Matrix4f modelView, Matrix4f projection, double cameraX, double cameraY, double cameraZ) {
            if (buffer == null) return;
            Matrix4f meshModelView = new Matrix4f(modelView).translate(
                    (float) (origin.getX() - cameraX), (float) (origin.getY() - cameraY), (float) (origin.getZ() - cameraZ));
            type.setupRenderState();
            buffer.bind();
            buffer.drawWithShader(meshModelView, projection, RenderSystem.getShader());
            VertexBuffer.unbind();
            type.clearRenderState();
        }

        void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            source = null;
            level = null;
        }
    }

    private static void closeMeshes() {
        GHOST_MESH.close();
        ghostEntityBlocks.clear();
        MARKER_MESH.close();
    }

    /**
     * Draws the previewed blocks as real, opaque block models instead of colored decals. The faces
     * of the {@link TimelineProjectionManager#GHOST_BLOCK_RENDER_LIMIT} positions nearest the
     * camera are baked once into a vertex buffer, so every frame is a single draw however many
     * blocks it holds; the buffer is rebuilt only when the preview changes or the camera has moved
     * well away from where it was built. Only faces not buried against a block of the target world
     * are baked.
     */
    private static void renderGhostBlocks(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, Level level,
                                           List<TimelineProjectionManager.ProjectionEntry> entries,
                                           Matrix4f modelView, Matrix4f projection,
                                           double cameraX, double cameraY, double cameraZ) {
        List<TimelineProjectionManager.ProjectionEntry> nearest = cachedNearest(GHOST_MESH_SELECTION, entries,
                cameraX, cameraY, cameraZ, TimelineProjectionManager.GHOST_BLOCK_RENDER_LIMIT, MESH_REUSE_DISTANCE_SQR);
        GHOST_MESH.ensure(level, nearest, cameraX, cameraY, cameraZ, DefaultVertexFormat.BLOCK,
                TimelineProjectionRenderer::ghostSignature, (builder, origin) -> buildGhostMesh(level, nearest, builder, origin));
        GHOST_MESH.draw(GHOST_BLOCK_RENDER_TYPE, modelView, projection, cameraX, cameraY, cameraZ);

        renderEntityGhostBlocks(poseStack, bufferSource, cameraX, cameraY, cameraZ);
    }

    /**
     * Blocks drawn by their item renderer (chests, beds, banners and the like) use entity textures
     * of their own, so they are drawn each frame through the real buffers instead of the mesh.
     */
    private static void renderEntityGhostBlocks(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                                double cameraX, double cameraY, double cameraZ) {
        if (ghostEntityBlocks.isEmpty()) return;

        Set<RenderType> entityTypes = new HashSet<>();
        MultiBufferSource entityRendered = renderType -> {
            entityTypes.add(renderType);
            return bufferSource.getBuffer(renderType);
        };
        for (TimelineProjectionManager.ProjectionEntry entry : ghostEntityBlocks) {
            BlockPos pos = entry.getPos();
            poseStack.pushPose();
            poseStack.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    entry.getTargetState(), poseStack, entityRendered, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        for (RenderType type : entityTypes) bufferSource.endBatch(type);
    }

    private static long ghostSignature(List<TimelineProjectionManager.ProjectionEntry> entries) {
        long hash = entries.size();
        for (TimelineProjectionManager.ProjectionEntry entry : entries) {
            hash = hash * 31L + entry.getPos().asLong();
            hash = hash * 31L + Block.getId(entry.getTargetState());
        }
        return hash;
    }

    private static void buildGhostMesh(Level level, List<TimelineProjectionManager.ProjectionEntry> selected,
                                       BufferBuilder builder, BlockPos origin) {
        ghostEntityBlocks.clear();
        PoseStack poseStack = new PoseStack();
        for (TimelineProjectionManager.ProjectionEntry entry : selected) {
            BlockState state = entry.getTargetState();
            if (state.isAir()) continue;
            if (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
                ghostEntityBlocks.add(entry);
                continue;
            }
            if (state.getRenderShape() != RenderShape.MODEL) continue;

            BlockPos pos = entry.getPos();
            poseStack.pushPose();
            poseStack.translate(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
            putVisibleFaces(poseStack.last(), builder, level, pos, state);
            poseStack.popPose();
        }
    }

    /**
     * Submits the quads of state's model that are not buried: each side's quads only while that side
     * is not covered by a block of the target world, plus the model's interior quads, which have no
     * side to test.
     */
    private static void putVisibleFaces(PoseStack.Pose pose, VertexConsumer buffer, Level level, BlockPos pos, BlockState state) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
        BlockColors blockColors = minecraft.getBlockColors();
        long seed = state.getSeed(pos);

        for (Direction direction : DIRECTIONS) {
            if (!ghostFaceExposed(level, pos, state, direction)) continue;

            // A face is lit by the space in front of it, as in a real block's flat lighting.
            int light = LevelRenderer.getLightColor(level, LIGHT_POS.setWithOffset(pos, direction));
            GHOST_RANDOM.setSeed(seed);
            putQuads(pose, buffer, model.getQuads(state, direction, GHOST_RANDOM, ModelData.EMPTY, null), blockColors, state, level, pos, light);
        }

        GHOST_RANDOM.setSeed(seed);
        putQuads(pose, buffer, model.getQuads(state, null, GHOST_RANDOM, ModelData.EMPTY, null), blockColors, state, level, pos,
                surroundingLight(level, pos));
    }

    /** The brightest light at pos and the blocks touching it, as a block's own spot may be solid and so dark. */
    private static int surroundingLight(Level level, BlockPos pos) {
        int block = LightTexture.block(LevelRenderer.getLightColor(level, pos));
        int sky = LightTexture.sky(LevelRenderer.getLightColor(level, pos));
        for (Direction direction : DIRECTIONS) {
            int light = LevelRenderer.getLightColor(level, LIGHT_POS.setWithOffset(pos, direction));
            block = Math.max(block, LightTexture.block(light));
            sky = Math.max(sky, LightTexture.sky(light));
        }
        return LightTexture.pack(block, sky);
    }

    private static void putQuads(PoseStack.Pose pose, VertexConsumer buffer, List<BakedQuad> quads, BlockColors blockColors,
                                 BlockState state, Level level, BlockPos pos, int light) {
        for (BakedQuad quad : quads) {
            // The same fixed brightness per facing that vanilla gives a block's faces.
            float shade = level.getShade(quad.getDirection(), quad.isShade());
            float r = shade, g = shade, b = shade;
            if (quad.isTinted()) {
                int tint = blockColors.getColor(state, level, pos, quad.getTintIndex());
                r *= (tint >> 16 & 0xFF) / 255.0F;
                g *= (tint >> 8 & 0xFF) / 255.0F;
                b *= (tint & 0xFF) / 255.0F;
            }
            buffer.putBulkData(pose, quad, r, g, b, 1.0F, light, OverlayTexture.NO_OVERLAY);
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
        return cachedNearest(cache, entries, cameraX, cameraY, cameraZ, limit, NEAREST_REUSE_DISTANCE_SQR);
    }

    private static List<TimelineProjectionManager.ProjectionEntry> cachedNearest(
            NearestCache cache, List<TimelineProjectionManager.ProjectionEntry> entries,
            double cameraX, double cameraY, double cameraZ, int limit, double reuseDistanceSqr) {
        if (entries.size() <= limit) return entries;

        double dx = cameraX - cache.x, dy = cameraY - cache.y, dz = cameraZ - cache.z;
        if (cache.source == entries && cache.limit == limit
                && dx * dx + dy * dy + dz * dz < reuseDistanceSqr) {
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

    /** Whether a decal on a block's face can be seen: the face does not sit against an opaque neighbour. */
    private static boolean markerFaceExposed(Level level, BlockPos pos, Direction direction) {
        NEIGHBOUR.setWithOffset(pos, direction);
        return !level.getBlockState(NEIGHBOUR).isSolidRender(level, NEIGHBOUR);
    }

    /**
     * Whether a ghost block's face is worth drawing: what hides it is the neighbour as it will be
     * after the jump, so a face between two ghost blocks is skipped and one against a block that is
     * about to be removed is kept.
     */
    private static boolean ghostFaceExposed(Level level, BlockPos pos, BlockState state, Direction direction) {
        NEIGHBOUR.setWithOffset(pos, direction);
        BlockState neighbour = TimelineProjectionManager.getTargetStateAt(level, NEIGHBOUR);
        if (state.skipRendering(neighbour, direction)) return false;
        return !neighbour.isSolidRender(level, NEIGHBOUR);
    }

    /** Adds a small square decal centered on each of the block's exposed faces, just outside the surface. */
    private static void putFaceMarkers(Matrix4f matrix, VertexConsumer buffer, Level level, BlockPos pos,
                                       float x, float y, float z, float r, float g, float b) {
        float lo = 0.5F - MARKER_SIZE / 2.0F;
        float hi = 0.5F + MARKER_SIZE / 2.0F;

        if (markerFaceExposed(level, pos, Direction.WEST)) {
            quad(matrix, buffer, r, g, b, 1.0F, x - FACE_INSET, y + lo, z + lo, x - FACE_INSET, y + lo, z + hi,
                    x - FACE_INSET, y + hi, z + hi, x - FACE_INSET, y + hi, z + lo);
        }
        if (markerFaceExposed(level, pos, Direction.EAST)) {
            quad(matrix, buffer, r, g, b, 1.0F, x + 1 + FACE_INSET, y + lo, z + hi, x + 1 + FACE_INSET, y + lo, z + lo,
                    x + 1 + FACE_INSET, y + hi, z + lo, x + 1 + FACE_INSET, y + hi, z + hi);
        }
        if (markerFaceExposed(level, pos, Direction.DOWN)) {
            quad(matrix, buffer, r, g, b, 1.0F, x + lo, y - FACE_INSET, z + hi, x + lo, y - FACE_INSET, z + lo,
                    x + hi, y - FACE_INSET, z + lo, x + hi, y - FACE_INSET, z + hi);
        }
        if (markerFaceExposed(level, pos, Direction.UP)) {
            quad(matrix, buffer, r, g, b, 1.0F, x + lo, y + 1 + FACE_INSET, z + lo, x + lo, y + 1 + FACE_INSET, z + hi,
                    x + hi, y + 1 + FACE_INSET, z + hi, x + hi, y + 1 + FACE_INSET, z + lo);
        }
        if (markerFaceExposed(level, pos, Direction.NORTH)) {
            quad(matrix, buffer, r, g, b, 1.0F, x + hi, y + lo, z - FACE_INSET, x + lo, y + lo, z - FACE_INSET,
                    x + lo, y + hi, z - FACE_INSET, x + hi, y + hi, z - FACE_INSET);
        }
        if (markerFaceExposed(level, pos, Direction.SOUTH)) {
            quad(matrix, buffer, r, g, b, 1.0F, x + lo, y + lo, z + 1 + FACE_INSET, x + hi, y + lo, z + 1 + FACE_INSET,
                    x + hi, y + hi, z + 1 + FACE_INSET, x + lo, y + hi, z + 1 + FACE_INSET);
        }
    }

    /** Adds the faces of a small cube floating in the middle of the block. */
    private static void putCenterCube(Matrix4f matrix, VertexConsumer buffer, float x, float y, float z,
                                      float r, float g, float b) {
        float minX = x + 0.5F - MARKER_SIZE / 2.0F, maxX = x + 0.5F + MARKER_SIZE / 2.0F;
        float minY = y + 0.5F - MARKER_SIZE / 2.0F, maxY = y + 0.5F + MARKER_SIZE / 2.0F;
        float minZ = z + 0.5F - MARKER_SIZE / 2.0F, maxZ = z + 0.5F + MARKER_SIZE / 2.0F;

        quad(matrix, buffer, r, g, b, 1.0F, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ);
        quad(matrix, buffer, r, g, b, 1.0F, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ);
        quad(matrix, buffer, r, g, b, 1.0F, minX, minY, maxZ, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ);
        quad(matrix, buffer, r, g, b, 1.0F, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        quad(matrix, buffer, r, g, b, 1.0F, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ);
        quad(matrix, buffer, r, g, b, 1.0F, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ);
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
