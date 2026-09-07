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

/** Renders Temporal Glue's in-world selections while a Temporal Glue is held: a translucent
 * outlined box for every known glued region, plus a live preview box from the pending corner
 * to the crosshair. */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class GlueSelectionRenderer {
    private static final float[] FILL_COLOR = {0.55F, 0.85F, 1.0F};
    private static final float FILL_ALPHA = 0.12F;
    private static final float[] OUTLINE_COLOR = {0.35F, 0.75F, 1.0F};
    private static final float OUTLINE_ALPHA = 0.9F;
    private static final float OUTLINE_THICKNESS = 0.02F;

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
        BoxOutlineRenderer.renderBox(matrix, buffer,
                region.minX(), region.minY(), region.minZ(),
                region.maxX() + 1.0D, region.maxY() + 1.0D, region.maxZ() + 1.0D,
                camX, camY, camZ, fillColor, fillAlpha, outlineColor, outlineAlpha, thickness);
    }
}
