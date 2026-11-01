package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.item.AntiTemporalGlueItem;
import io.github.tofithepuppycat.temporalindustries.item.GlueTargeting;
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

/**
 * Renders Anti-Temporal Glue's in-world selections while a Anti-Temporal Glue is held: a translucent outlined
 * box for every known glued region, plus a live preview box from the pending corner to the
 * crosshair.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class GlueSelectionRenderer {
    private static final float FILL_ALPHA = 0.12F;
    private static final float OUTLINE_ALPHA = 0.9F;
    private static final float OUTLINE_THICKNESS = 0.02F;

    private static final float[] ORDER_COLOR = rgb(EntropyType.ORDER.color());
    private static final float[] CHAOS_COLOR = rgb(EntropyType.CHAOS.color());

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
        boolean tracked = heldStack.getItem() instanceof TemporalGlueItem;
        List<BoundingBox> regions = tracked
                ? GlueSelectionClientState.getTrackedRegions(dimension) : GlueSelectionClientState.getRegions(dimension);
        Optional<BlockPos> pendingCorner = tracked
                ? TemporalGlueItem.getPendingCorner(heldStack) : AntiTemporalGlueItem.getPendingCorner(heldStack);
        BoundingBox preview = null;
        if (pendingCorner.isPresent()) {
            BlockPos lookedAt = currentTarget(minecraft, level);
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

        float[] glueColor = tracked ? CHAOS_COLOR : ORDER_COLOR;
        for (BoundingBox region : regions) {
            renderBox(matrix, quadBuffer, region, camX, camY, camZ,
                    glueColor, FILL_ALPHA, glueColor, OUTLINE_ALPHA, OUTLINE_THICKNESS);
        }
        if (preview != null) {
            renderBox(matrix, quadBuffer, preview, camX, camY, camZ,
                    glueColor, PREVIEW_FILL_ALPHA, glueColor, PREVIEW_OUTLINE_ALPHA, PREVIEW_THICKNESS);
        }

        bufferSource.endBatch(RenderType.debugQuads());
    }

    private static ItemStack holdingGlue(Player player) {
        for (ItemStack stack : new ItemStack[] {player.getMainHandItem(), player.getOffhandItem()}) {
            if (stack.getItem() instanceof AntiTemporalGlueItem || stack.getItem() instanceof TemporalGlueItem) return stack;
        }
        return null;
    }

    /** The block a click would pick: the one looked at, or the air block at the end of reach. */
    @Nullable
    private static BlockPos currentTarget(Minecraft minecraft, Level level) {
        if (minecraft.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return GlueTargeting.airTarget(level, minecraft.player).orElse(null);
    }

    private static float[] rgb(int color) {
        return new float[] {((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F};
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
