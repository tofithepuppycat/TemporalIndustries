package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.block.WithergenicReactor;
import io.github.tofithepuppycat.temporalindustries.block.WithergenicReactorStructure;
import io.github.tofithepuppycat.temporalindustries.block.entity.WithergenicReactorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/** Draws the captured Wither floating in the middle of a {@link WithergenicReactorBlockEntity}'s sphere. */
@SuppressWarnings("null")
public class WithergenicReactorRenderer implements BlockEntityRenderer<WithergenicReactorBlockEntity> {
    /** Scales the Wither's 3.5 block height down to fit the 3 block chamber. */
    private static final float SCALE = 0.7F;
    private static final double CHAMBER_RADIUS = 6.0;
    /** Radii of the nested glow shells and the alpha of each at full brightness. */
    private static final float[] GLOW_RADII = {1.45F, 1.15F, 0.85F};
    private static final float[] GLOW_ALPHAS = {0.10F, 0.14F, 0.2F};
    private static final int GLOW_STACKS = 10;
    private static final int GLOW_SLICES = 16;

    @Nullable private WitherBoss wither;
    @Nullable private Level witherLevel;

    public WithergenicReactorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(@NotNull WithergenicReactorBlockEntity be, float partialTick, @NotNull PoseStack poseStack,
                       @NotNull MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null || !be.isOccupied() || !be.isFormed()) return;

        WitherBoss entity = getWither(level);
        if (entity == null) return;

        BlockPos origin = be.getBlockPos();
        var facing = be.getBlockState().getValue(WithergenicReactor.FACING);
        BlockPos chamber = WithergenicReactorStructure.chamberPosition(origin, facing);

        entity.tickCount = (int) (level.getGameTime() % 1_000_000L);
        float bob = (float) Math.sin((level.getGameTime() + partialTick) * 0.08) * 0.1F;

        poseStack.pushPose();
        poseStack.translate(chamber.getX() - origin.getX() + 0.5, chamber.getY() - origin.getY() + 0.5 - 1.1 + bob, chamber.getZ() - origin.getZ() + 0.5);
        poseStack.scale(SCALE, SCALE, SCALE);
        float yaw = facing.getOpposite().toYRot();
        Minecraft.getInstance().getEntityRenderDispatcher().render(entity, 0.0, 0.0, 0.0, yaw, partialTick, poseStack, buffer, packedLight);
        poseStack.popPose();

        if (be.isRunning()) {
            float pulse = 0.75F + 0.25F * (float) Math.sin((level.getGameTime() + partialTick) * 0.15);
            poseStack.pushPose();
            poseStack.translate(chamber.getX() - origin.getX() + 0.5, chamber.getY() - origin.getY() + 0.5, chamber.getZ() - origin.getZ() + 0.5);
            renderGlow(poseStack.last().pose(), buffer.getBuffer(RenderType.lightning()), pulse);
            poseStack.popPose();
        }
    }

    /** Nested translucent shells in the Chaos colour, drawn from both sides so they read from inside and out. */
    private static void renderGlow(Matrix4f matrix, VertexConsumer consumer, float pulse) {
        int color = EntropyType.CHAOS.color();
        float red = ((color >> 16) & 0xFF) / 255F;
        float green = ((color >> 8) & 0xFF) / 255F;
        float blue = (color & 0xFF) / 255F;
        for (int shell = 0; shell < GLOW_RADII.length; shell++) {
            float radius = GLOW_RADII[shell] * (0.96F + 0.04F * pulse);
            float alpha = GLOW_ALPHAS[shell] * pulse;
            for (int stack = 0; stack < GLOW_STACKS; stack++) {
                float lat0 = (float) Math.PI * stack / GLOW_STACKS;
                float lat1 = (float) Math.PI * (stack + 1) / GLOW_STACKS;
                for (int slice = 0; slice < GLOW_SLICES; slice++) {
                    float lon0 = (float) (2 * Math.PI) * slice / GLOW_SLICES;
                    float lon1 = (float) (2 * Math.PI) * (slice + 1) / GLOW_SLICES;
                    float[][] corners = {
                            point(radius, lat0, lon0), point(radius, lat1, lon0), point(radius, lat1, lon1), point(radius, lat0, lon1)
                    };
                    for (int i = 0; i < 4; i++) glowVertex(matrix, consumer, corners[i], red, green, blue, alpha);
                    for (int i = 3; i >= 0; i--) glowVertex(matrix, consumer, corners[i], red, green, blue, alpha);
                }
            }
        }
    }

    private static float[] point(float radius, float lat, float lon) {
        float ring = (float) Math.sin(lat) * radius;
        return new float[]{ring * (float) Math.cos(lon), (float) Math.cos(lat) * radius, ring * (float) Math.sin(lon)};
    }

    private static void glowVertex(Matrix4f matrix, VertexConsumer consumer, float[] p, float red, float green, float blue, float alpha) {
        consumer.addVertex(matrix, p[0], p[1], p[2]).setColor(red, green, blue, alpha);
    }

    @Nullable
    private WitherBoss getWither(Level level) {
        if (wither == null || witherLevel != level) {
            wither = EntityType.WITHER.create(level);
            witherLevel = level;
        }
        return wither;
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull WithergenicReactorBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(CHAMBER_RADIUS);
    }
}
