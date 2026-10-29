package io.github.tofithepuppycat.temporalindustries.client.timeline;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

/**
 * Renders the entities a jump to the selected commit would restore as translucent ghosts at their
 * recorded positions. The ghosts are built from the recorded entity data and are never added to the
 * level, so they neither tick nor take part in anything but rendering.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class TimelineEntityGhostRenderer {
    private static final float[] TINT = {0.6F, 0.85F, 1.0F};
    private static final float ALPHA_MIN = 0.35F;
    private static final float ALPHA_MAX = 0.7F;
    private static final long PULSE_PERIOD_MS = 1400L;
    private static final double MAX_RENDER_DISTANCE_SQR = 128.0D * 128.0D;

    @Nullable private static List<CompoundTag> builtFrom;
    @Nullable private static ClientLevel builtLevel;
    private static final List<Entity> GHOSTS = new ArrayList<>();

    private TimelineEntityGhostRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !TimelineProjectionManager.hasActivePreview()) return;

        rebuildIfStale(level, TimelineProjectionManager.getPreviewEntities());
        if (GHOSTS.isEmpty()) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();

        double phase = (System.currentTimeMillis() % PULSE_PERIOD_MS) / (double) PULSE_PERIOD_MS;
        float alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * (0.5F + 0.5F * (float) Math.sin(2.0D * Math.PI * phase));

        dispatcher.setRenderShadow(false);
        try {
            for (Iterator<Entity> it = GHOSTS.iterator(); it.hasNext(); ) {
                Entity ghost = it.next();
                if (ghost.distanceToSqr(camPos) > MAX_RENDER_DISTANCE_SQR) continue;

                ghost.tickCount = (int) level.getGameTime();
                int light = LevelRenderer.getLightColor(level, BlockPos.containing(ghost.getX(), ghost.getEyeY(), ghost.getZ()));
                try {
                    dispatcher.render(ghost, ghost.getX() - camPos.x, ghost.getY() - camPos.y, ghost.getZ() - camPos.z,
                            ghost.getYRot(), partialTick, poseStack, new GhostBufferSource(bufferSource, textureOf(dispatcher, ghost), alpha), light);
                } catch (RuntimeException e) {
                    // A renderer that cannot cope with a detached entity must not take the frame down.
                    it.remove();
                }
            }
        } finally {
            dispatcher.setRenderShadow(minecraft.options.entityShadows().get());
        }
        bufferSource.endBatch();
    }

    private static void rebuildIfStale(ClientLevel level, List<CompoundTag> source) {
        if (builtFrom == source && builtLevel == level) return;

        GHOSTS.clear();
        for (CompoundTag tag : source) {
            Entity entity = EntityType.loadEntityRecursive(tag.copy(), level, Function.identity());
            if (entity == null) continue;
            entity.xo = entity.xOld = entity.getX();
            entity.yo = entity.yOld = entity.getY();
            entity.zo = entity.zOld = entity.getZ();
            entity.yRotO = entity.getYRot();
            entity.xRotO = entity.getXRot();
            // Renderers interpolate from the previous-frame values, which start at zero on a fresh entity.
            if (entity instanceof LivingEntity living) {
                living.yBodyRotO = living.yBodyRot;
                living.yHeadRotO = living.yHeadRot;
            }
            GHOSTS.add(entity);
        }
        builtFrom = source;
        builtLevel = level;
    }

    @Nullable
    private static ResourceLocation textureOf(EntityRenderDispatcher dispatcher, Entity entity) {
        return dispatcher.getRenderer(entity).getTextureLocation(entity);
    }

    /**
     * Redirects the entity's main model to a translucent render type and tints everything drawn
     * through it. The first render type an entity asks for is its body; layers such as armor are
     * left on their own render types and only tinted.
     */
    private static final class GhostBufferSource implements MultiBufferSource {
        private final MultiBufferSource delegate;
        @Nullable private final ResourceLocation bodyTexture;
        private final float alpha;
        @Nullable private RenderType bodyType;

        GhostBufferSource(MultiBufferSource delegate, @Nullable ResourceLocation bodyTexture, float alpha) {
            this.delegate = delegate;
            this.bodyTexture = bodyTexture;
            this.alpha = alpha;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (bodyType == null) bodyType = type;
            RenderType effective = type == bodyType && bodyTexture != null ? RenderType.entityTranslucent(bodyTexture) : type;
            return new TintedConsumer(delegate.getBuffer(effective), TINT[0], TINT[1], TINT[2], alpha);
        }
    }

    private static final class TintedConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float red;
        private final float green;
        private final float blue;
        private final float alpha;

        TintedConsumer(VertexConsumer delegate, float red, float green, float blue, float alpha) {
            this.delegate = delegate;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor((int) (r * red), (int) (g * green), (int) (b * blue), (int) (a * alpha));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
