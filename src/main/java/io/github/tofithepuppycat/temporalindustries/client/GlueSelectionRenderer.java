package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.TemporalGlueItem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Optional;

/** Renders Temporal Glue's selections in-world while a Temporal Glue is held in either hand: a
 * translucent fill plus a thin outline for every known glued region (see
 * {@link GlueSelectionClientState}), and, once a starting corner has been selected, a live preview
 * box tracking the crosshair from that corner to whatever block is currently looked at. */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class GlueSelectionRenderer {
    private static final float[] FILL_COLOR = {0.55F, 0.85F, 1.0F};
    private static final float FILL_ALPHA = 0.12F;
    private static final float[] OUTLINE_COLOR = {0.35F, 0.75F, 1.0F};
    private static final float OUTLINE_ALPHA = 0.9F;
    private static final float OUTLINE_THICKNESS = 0.02F;
    /** Grows every rendered box outward by this much on every axis, so its faces never sit exactly
     * on the same plane as a real block face — that exact coincidence is what was causing z-fighting
     * against world geometry at the selection's boundary. */
    private static final float SURFACE_OUTSET = 0.004F;

    private static final float[] PREVIEW_COLOR = {1.0F, 0.85F, 0.25F};
    private static final float PREVIEW_FILL_ALPHA = 0.10F;
    private static final float PREVIEW_OUTLINE_ALPHA = 0.85F;
    private static final float PREVIEW_THICKNESS = 0.025F;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }

        ItemStack heldStack = holdingGlue(minecraft.player);
        if (heldStack == null) {
            return;
        }

        ResourceLocation dimension = level.dimension().location();
        List<BoundingBox> regions = GlueSelectionClientState.getRegions(dimension);
        Optional<BlockPos> pendingCorner = TemporalGlueItem.getPendingCorner(heldStack);
        BoundingBox preview = null;
        if (pendingCorner.isPresent()) {
            BlockPos lookedAt = currentLookedAtBlock(minecraft);
            preview = BoundingBox.fromCorners(pendingCorner.get(), lookedAt != null ? lookedAt : pendingCorner.get());
        }

        if (regions.isEmpty() && preview == null) {
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

        for (BoundingBox region : regions) {
            renderBox(matrix, quadBuffer, region, camX, camY, camZ,
                    FILL_COLOR, FILL_ALPHA, OUTLINE_COLOR, OUTLINE_ALPHA, OUTLINE_THICKNESS);
        }
        if (preview != null) {
            renderBox(matrix, quadBuffer, preview, camX, camY, camZ,
                    PREVIEW_COLOR, PREVIEW_FILL_ALPHA, PREVIEW_COLOR, PREVIEW_OUTLINE_ALPHA, PREVIEW_THICKNESS);
        }

        bufferSource.endBatch(RenderType.debugQuads());
    }

    private static ItemStack holdingGlue(Player player) {
        if (player.getMainHandItem().getItem() instanceof TemporalGlueItem) return player.getMainHandItem();
        if (player.getOffhandItem().getItem() instanceof TemporalGlueItem) return player.getOffhandItem();
        return null;
    }

    @Nullable
    private static BlockPos currentLookedAtBlock(Minecraft minecraft) {
        if (minecraft.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private static void renderBox(Matrix4f matrix, VertexConsumer buffer, BoundingBox region,
                                   double camX, double camY, double camZ,
                                   float[] fillColor, float fillAlpha, float[] outlineColor, float outlineAlpha,
                                   float thickness) {
        float minX = (float) (region.minX() - SURFACE_OUTSET - camX);
        float minY = (float) (region.minY() - SURFACE_OUTSET - camY);
        float minZ = (float) (region.minZ() - SURFACE_OUTSET - camZ);
        float maxX = (float) (region.maxX() + 1.0D + SURFACE_OUTSET - camX);
        float maxY = (float) (region.maxY() + 1.0D + SURFACE_OUTSET - camY);
        float maxZ = (float) (region.maxZ() + 1.0D + SURFACE_OUTSET - camZ);

        box(matrix, buffer, minX, minY, minZ, maxX, maxY, maxZ, fillColor[0], fillColor[1], fillColor[2], fillAlpha);
        outline(matrix, buffer, minX, minY, minZ, maxX, maxY, maxZ, thickness,
                outlineColor[0], outlineColor[1], outlineColor[2], outlineAlpha);
    }

    /** Draws a box's 12 edges as thin boxes rather than GL lines, so they have real thickness
     * regardless of view distance/GL line-width support. Each edge varies along exactly one axis
     * (boxes here are always axis-aligned); the other two axes get inflated by thickness/2 on both
     * sides to give it a square cross-section. */
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
