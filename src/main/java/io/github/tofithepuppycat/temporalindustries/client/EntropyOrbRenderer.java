package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws {@link EntropyOrbEntity} the same way vanilla draws an xp orb (a small billboarded quad,
 * reusing vanilla's experience_orb.png), but tinted by the orb's {@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyType}
 * color instead of the xp rainbow shimmer.
 */
public class EntropyOrbRenderer extends EntityRenderer<EntropyOrbEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/experience_orb.png");
    private static final RenderType RENDER_TYPE = RenderType.itemEntityTranslucentCull(TEXTURE);

    public EntropyOrbRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.15F;
        this.shadowStrength = 0.75F;
    }

    @Override
    protected int getBlockLightLevel(EntropyOrbEntity entity, BlockPos pos) {
        return Mth.clamp(super.getBlockLightLevel(entity, pos) + 7, 0, 15);
    }

    @Override
    public void render(EntropyOrbEntity entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        float u0 = 0.0F;
        float u1 = 16.0F / 64.0F;
        float v0 = 0.0F;
        float v1 = 16.0F / 64.0F;

        int color = entity.getEntropyType().color();
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;

        poseStack.translate(0.0F, 0.1F, 0.0F);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(0.3F, 0.3F, 0.3F);
        VertexConsumer consumer = buffer.getBuffer(RENDER_TYPE);
        PoseStack.Pose pose = poseStack.last();
        vertex(consumer, pose, -0.5F, -0.25F, red, green, blue, u0, v1, packedLight);
        vertex(consumer, pose, 0.5F, -0.25F, red, green, blue, u1, v1, packedLight);
        vertex(consumer, pose, 0.5F, 0.75F, red, green, blue, u1, v0, packedLight);
        vertex(consumer, pose, -0.5F, 0.75F, red, green, blue, u0, v0, packedLight);
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, int red, int green, int blue,
            float u, float v, int packedLight) {
        consumer.addVertex(pose, x, y, 0.0F)
                .setColor(red, green, blue, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(packedLight)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(EntropyOrbEntity entity) {
        return TEXTURE;
    }
}
