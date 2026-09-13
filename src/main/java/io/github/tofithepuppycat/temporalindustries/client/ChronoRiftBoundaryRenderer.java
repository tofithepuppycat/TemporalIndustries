package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftDimension;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Renders a pulsing translucent wall along every edge where a tracked Chrono Rift chunk borders
 * an untracked one, so the boundary of "explorable" rift terrain is visible before a player walks
 * into the void — {@link ChronoRiftBorderListener} is what actually stops them. */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class ChronoRiftBoundaryRenderer {
    private static final int SCAN_RADIUS_CHUNKS = 10;
    private static final double WALL_THICKNESS = 0.2D;
    private static final double WALL_MIN_Y = -64.0D;
    private static final double WALL_MAX_Y = 320.0D;
    private static final float[] WALL_COLOR = {0.65F, 0.25F, 0.9F};
    private static final float BASE_ALPHA = 0.16F;
    private static final float PULSE_ALPHA = 0.14F;

    private ChronoRiftBoundaryRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.player == null || level.dimension() != ChronoRiftDimension.LEVEL_KEY) {
            return;
        }

        ChunkPos center = new ChunkPos(minecraft.player.blockPosition());

        PoseStack poseStack = event.getPoseStack();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer quadBuffer = bufferSource.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = poseStack.last().pose();

        float phase = (float) (level.getGameTime() % 1000) + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float alpha = BASE_ALPHA + PULSE_ALPHA * (0.5F + 0.5F * (float) Math.sin(phase * 0.05));

        for (int dx = -SCAN_RADIUS_CHUNKS; dx <= SCAN_RADIUS_CHUNKS; dx++) {
            for (int dz = -SCAN_RADIUS_CHUNKS; dz <= SCAN_RADIUS_CHUNKS; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                if (!ChronoRiftClientState.isTracked(pos)) continue;

                renderEdgeIfBoundary(matrix, quadBuffer, pos, pos.x + 1, pos.z, true, camX, camY, camZ, alpha);
                renderEdgeIfBoundary(matrix, quadBuffer, pos, pos.x - 1, pos.z, true, camX, camY, camZ, alpha);
                renderEdgeIfBoundary(matrix, quadBuffer, pos, pos.x, pos.z + 1, false, camX, camY, camZ, alpha);
                renderEdgeIfBoundary(matrix, quadBuffer, pos, pos.x, pos.z - 1, false, camX, camY, camZ, alpha);
            }
        }

        bufferSource.endBatch(RenderType.debugQuads());
    }

    private static void renderEdgeIfBoundary(Matrix4f matrix, VertexConsumer buffer, ChunkPos pos,
                                               int neighborX, int neighborZ, boolean xAxis,
                                               double camX, double camY, double camZ, float alpha) {
        ChunkPos neighbor = new ChunkPos(neighborX, neighborZ);
        if (ChronoRiftClientState.isTracked(neighbor)) return; // interior edge, no boundary to show

        double minX, minZ, maxX, maxZ;
        if (xAxis) {
            double edgeX = neighborX > pos.x ? pos.getMaxBlockX() + 1.0D : pos.getMinBlockX();
            minX = edgeX - WALL_THICKNESS / 2.0D;
            maxX = edgeX + WALL_THICKNESS / 2.0D;
            minZ = pos.getMinBlockZ();
            maxZ = pos.getMaxBlockZ() + 1.0D;
        } else {
            double edgeZ = neighborZ > pos.z ? pos.getMaxBlockZ() + 1.0D : pos.getMinBlockZ();
            minZ = edgeZ - WALL_THICKNESS / 2.0D;
            maxZ = edgeZ + WALL_THICKNESS / 2.0D;
            minX = pos.getMinBlockX();
            maxX = pos.getMaxBlockX() + 1.0D;
        }

        BoxOutlineRenderer.renderBox(matrix, buffer,
                minX, WALL_MIN_Y, minZ, maxX, WALL_MAX_Y, maxZ,
                camX, camY, camZ,
                WALL_COLOR, alpha, WALL_COLOR, 0.0F, 0.0F);
    }
}
