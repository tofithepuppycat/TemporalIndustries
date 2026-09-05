package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.EntropicPylonItem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/** Renders {@link EntropicPylonItem}'s currently marked positions in-world while it's held in
 * either hand - a red outline for every marked input, a blue one for every marked output. Reads
 * marks straight off the held stack's data components, same as the item itself, so there's no
 * separate client-side cache to keep in sync (compare {@link GlueSelectionRenderer}, which does
 * need one because its selections are server-authoritative). */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class EntropicPylonMarkRenderer {
    private static final float[] INPUT_COLOR = {1.0F, 0.25F, 0.25F};
    private static final float[] OUTPUT_COLOR = {0.35F, 0.55F, 1.0F};
    private static final float FILL_ALPHA = 0.15F;
    private static final float OUTLINE_ALPHA = 0.9F;
    private static final float OUTLINE_THICKNESS = 0.03F;

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

        ItemStack heldStack = holdingPylon(minecraft.player);
        if (heldStack == null) {
            return;
        }

        List<BlockPos> inputs = EntropicPylonItem.getInputs(heldStack);
        List<BlockPos> outputs = EntropicPylonItem.getOutputs(heldStack);
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

        bufferSource.endBatch(RenderType.debugQuads());
    }

    private static ItemStack holdingPylon(Player player) {
        if (player.getMainHandItem().getItem() instanceof EntropicPylonItem) return player.getMainHandItem();
        if (player.getOffhandItem().getItem() instanceof EntropicPylonItem) return player.getOffhandItem();
        return null;
    }

    private static void renderMark(Matrix4f matrix, VertexConsumer buffer, BlockPos pos,
                                    double camX, double camY, double camZ, float[] color) {
        BoxOutlineRenderer.renderBox(matrix, buffer,
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D,
                camX, camY, camZ, color, FILL_ALPHA, color, OUTLINE_ALPHA, OUTLINE_THICKNESS);
    }
}
