package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronoProjectorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.device.ChronoRecording;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a translucent "ghost" of whatever a Chrono Loop Projector is replaying, using the
 * recording owner's resolved skin. Purely client-side and visual: it only reads the block entity's
 * synced playback state and tint. A plain {@link PlayerModel} is drawn directly with a forced
 * translucent render type (rather than the normal player renderer) so tint/alpha apply reliably.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class ChronoGhostRenderer {
    private static final Set<ChronoProjectorBlockEntity> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final UUID UNKNOWN_OWNER = new UUID(0L, 0L);

    private static PlayerModel<LivingEntity> wideModel;
    private static PlayerModel<LivingEntity> slimModel;

    /** One fake player per recording owner so each ghost keeps its own resolved skin. */
    private static final Map<UUID, RemotePlayer> GHOSTS = new HashMap<>();
    @Nullable private static ClientLevel ghostsLevel;

    private ChronoGhostRenderer() {}

    public static void register(ChronoProjectorBlockEntity projector) {
        ACTIVE.add(projector);
    }

    public static void unregister(ChronoProjectorBlockEntity projector) {
        ACTIVE.remove(projector);
    }

    @SuppressWarnings("unchecked")
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long gameTime = level.getGameTime();

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        boolean renderedAny = false;
        for (ChronoProjectorBlockEntity projector : List.copyOf(ACTIVE)) {
            if (projector.isRemoved()) {
                ACTIVE.remove(projector);
                continue;
            }

            ChronoRecording recording = projector.getCachedRecording();
            if (recording == null) continue;

            ResourceLocation recordingDimension = recording.getDimension();
            if (recordingDimension != null && !recordingDimension.equals(level.dimension().location())) {
                continue;
            }

            double t = projector.computePlaybackProgress(gameTime, partialTick);
            if (t < 0) continue;

            int i0 = (int) Math.floor(t);
            ChronoRecording.Frame a = recording.frameAt(i0);
            ChronoRecording.Frame b = recording.frameAt(i0 + 1);
            float frac = (float) (t - Math.floor(t));

            double x = recording.getStartX() + lerp(frac, a.x(), b.x());
            double y = recording.getStartY() + lerp(frac, a.y(), b.y());
            double z = recording.getStartZ() + lerp(frac, a.z(), b.z());
            float yaw = rotLerp(frac, a.yaw(), b.yaw());
            float pitch = lerp(frac, a.pitch(), b.pitch());
            boolean crouching = frac < 0.5F ? a.crouching() : b.crouching();

            RemotePlayer ghost = fakeGhost(level, recording);
            PlayerSkin skin = ghost.getSkin();
            PlayerModel<LivingEntity> playerModel = modelFor(skin.model());

            ghost.setPos(x, y, z);
            ghost.setYRot(yaw);
            ghost.setXRot(pitch);
            ghost.yBodyRot = yaw;
            ghost.yHeadRot = yaw;
            ghost.setPose(crouching ? Pose.CROUCHING : Pose.STANDING);

            playerModel.crouching = crouching;
            playerModel.young = false;
            playerModel.setupAnim(ghost, recording.limbSwingAt(t), recording.limbSwingAmountAt(t),
                    gameTime + partialTick, 0.0F, pitch);

            int packedLight = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));

            poseStack.pushPose();
            poseStack.translate(x - camPos.x, y - camPos.y, z - camPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.scale(0.9375F, 0.9375F, 0.9375F);
            poseStack.translate(0.0F, -1.501F, 0.0F);

            VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityTranslucent(skin.texture()));
            playerModel.renderToBuffer(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY, projector.getGhostColor());

            poseStack.popPose();
            renderedAny = true;
        }

        if (renderedAny) {
            bufferSource.endBatch();
        }
    }

    private static PlayerModel<LivingEntity> modelFor(PlayerSkin.Model type) {
        if (wideModel == null) {
            var models = Minecraft.getInstance().getEntityModels();
            wideModel = new PlayerModel<>(models.bakeLayer(ModelLayers.PLAYER), false);
            slimModel = new PlayerModel<>(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        }
        return type == PlayerSkin.Model.SLIM ? slimModel : wideModel;
    }

    /** Builds a fake player from the recording's stored owner id/name so {@link RemotePlayer#getSkin()}
     * can resolve it the normal vanilla way. */
    private static RemotePlayer fakeGhost(ClientLevel level, ChronoRecording recording) {
        if (ghostsLevel != level) {
            GHOSTS.clear();
            ghostsLevel = level;
        }

        UUID ownerId = recording.getOwnerId();
        UUID key = ownerId != null ? ownerId : UNKNOWN_OWNER;

        RemotePlayer ghost = GHOSTS.get(key);
        if (ghost == null) {
            String ownerName = recording.getOwnerName();
            GameProfile profile = new GameProfile(ownerId != null ? ownerId : UUID.randomUUID(),
                    (ownerName == null || ownerName.isBlank()) ? "ChronoGhost" : ownerName);
            ghost = new RemotePlayer(level, profile);
            GHOSTS.put(key, ghost);
        }
        return ghost;
    }

    private static float lerp(float pct, float a, float b) {
        return a + pct * (b - a);
    }

    private static float rotLerp(float pct, float a, float b) {
        float diff = b - a;
        while (diff < -180.0F) diff += 360.0F;
        while (diff >= 180.0F) diff -= 360.0F;
        return a + pct * diff;
    }
}
