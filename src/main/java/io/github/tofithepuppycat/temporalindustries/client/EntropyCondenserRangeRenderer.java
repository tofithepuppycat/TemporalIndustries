package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.EntropyCondenser;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyCondenserBlockEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Renders an Entropy Condenser's absorb-range perimeter in-world while
 * {@link EntropyCondenserRangeClientState} points at it (toggled from its GUI's "Show Range"
 * button), using the same {@link EntropyCondenserBlockEntity#absorbArea} the server uses to catch
 * orbs, so the box always matches what actually gets absorbed. */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class EntropyCondenserRangeRenderer {
    private static final float[] FILL_COLOR = {0.55F, 1.0F, 0.65F};
    private static final float FILL_ALPHA = 0.12F;
    private static final float[] OUTLINE_COLOR = {0.35F, 1.0F, 0.45F};
    private static final float OUTLINE_ALPHA = 0.9F;
    private static final float OUTLINE_THICKNESS = 0.02F;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }

        ResourceLocation dimension = level.dimension().location();
        BlockPos pos = EntropyCondenserRangeClientState.getPos(dimension);
        if (pos == null) {
            return;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof EntropyCondenserBlockEntity condenser)) {
            return;
        }

        AABB area = EntropyCondenserBlockEntity.absorbArea(pos, level.getBlockState(pos).getValue(EntropyCondenser.FACING), condenser.getRange());

        PoseStack poseStack = event.getPoseStack();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer quadBuffer = bufferSource.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = poseStack.last().pose();

        BoxOutlineRenderer.renderBox(matrix, quadBuffer,
                area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ,
                camX, camY, camZ, FILL_COLOR, FILL_ALPHA, OUTLINE_COLOR, OUTLINE_ALPHA, OUTLINE_THICKNESS);

        bufferSource.endBatch(RenderType.debugQuads());
    }
}
