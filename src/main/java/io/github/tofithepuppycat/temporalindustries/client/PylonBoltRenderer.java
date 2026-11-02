package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Draws the lightning that runs along a pylon's links while it transfers: a jagged path of
 * camera-facing ribbons, shaded by {@link LightningShader}. The server refreshes a link's bolt
 * every couple of ticks while something flows, and a bolt that stops being refreshed fades out.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class PylonBoltRenderer {
    /** Ticks a bolt keeps showing after the server last refreshed it. */
    private static final int LIFETIME_TICKS = 5;
    /** Ticks a path keeps its shape before it jumps to a new one. */
    private static final float FLICKER_TICKS = 1.5F;
    /** Straight sub-segments per block of link length, within the bounds below. */
    private static final float SEGMENTS_PER_BLOCK = 1.5F;
    private static final int MIN_SEGMENTS = 6;
    private static final int MAX_SEGMENTS = 40;
    /** Largest sideways displacement of a joint off the straight line, in blocks. */
    private static final float JITTER = 0.28F;
    private static final double HALF_WIDTH = 0.09D;

    private record Bolt(BlockPos from, BlockPos to, int color, long expires) {}

    private static final Map<Long, Bolt> BOLTS = new HashMap<>();

    private PylonBoltRenderer() {}

    /** Starts or refreshes the bolt from {@code from} to {@code to}. */
    public static void add(BlockPos from, BlockPos to, int color) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        long key = from.asLong() * 31L + to.asLong();
        BOLTS.put(key, new Bolt(from.immutable(), to.immutable(), color, level.getGameTime() + LIFETIME_TICKS));
    }

    public static void clear() {
        BOLTS.clear();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || BOLTS.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || LightningShader.get() == null) {
            BOLTS.clear();
            return;
        }

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float now = level.getGameTime() + partial;

        PoseStack poseStack = event.getPoseStack();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;
        Matrix4f matrix = poseStack.last().pose();

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(LightningShader.renderType());

        for (Iterator<Map.Entry<Long, Bolt>> it = BOLTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Bolt> entry = it.next();
            Bolt bolt = entry.getValue();
            float remaining = bolt.expires() - now;
            if (remaining <= 0.0F) {
                it.remove();
                continue;
            }
            float alpha = Math.min(1.0F, remaining / 2.0F);
            long seed = entry.getKey() * 0x9E3779B97F4A7C15L + (long) Math.floor(now / FLICKER_TICKS);
            drawBolt(matrix, buffer, bolt, alpha, RandomSource.create(seed), camX, camY, camZ);
        }

        bufferSource.endBatch(LightningShader.renderType());
    }

    private static void drawBolt(Matrix4f matrix, VertexConsumer buffer, Bolt bolt, float alpha, RandomSource random,
                                 double camX, double camY, double camZ) {
        List<Vector3f> path = path(center(bolt.from()), center(bolt.to()), random);
        float[] along = new float[path.size()];
        for (int i = 1; i < path.size(); i++) {
            along[i] = along[i - 1] + path.get(i).distance(path.get(i - 1));
        }
        float length = along[along.length - 1];
        if (length < 1.0E-4F) return;

        float r = ((bolt.color() >> 16) & 0xFF) / 255.0F;
        float g = ((bolt.color() >> 8) & 0xFF) / 255.0F;
        float b = (bolt.color() & 0xFF) / 255.0F;

        for (int i = 0; i + 1 < path.size(); i++) {
            double ax = path.get(i).x() - camX, ay = path.get(i).y() - camY, az = path.get(i).z() - camZ;
            double bx = path.get(i + 1).x() - camX, by = path.get(i + 1).y() - camY, bz = path.get(i + 1).z() - camZ;
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            // The ribbon's width direction is perpendicular to both the segment and the view of its midpoint.
            double vx = (ax + bx) / 2, vy = (ay + by) / 2, vz = (az + bz) / 2;
            double sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;
            double sideLength = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sideLength < 1.0E-6D) continue;
            double scale = HALF_WIDTH / sideLength;
            sx *= scale;
            sy *= scale;
            sz *= scale;

            float u0 = along[i] / length;
            float u1 = along[i + 1] / length;
            buffer.addVertex(matrix, (float) (ax - sx), (float) (ay - sy), (float) (az - sz)).setColor(r, g, b, alpha).setUv(u0, 0.0F);
            buffer.addVertex(matrix, (float) (ax + sx), (float) (ay + sy), (float) (az + sz)).setColor(r, g, b, alpha).setUv(u0, 1.0F);
            buffer.addVertex(matrix, (float) (bx + sx), (float) (by + sy), (float) (bz + sz)).setColor(r, g, b, alpha).setUv(u1, 1.0F);
            buffer.addVertex(matrix, (float) (bx - sx), (float) (by - sy), (float) (bz - sz)).setColor(r, g, b, alpha).setUv(u1, 0.0F);
        }
    }

    /** A jagged path from {@code start} to {@code end}: straight pieces joined at jittered points, the ends fixed. */
    private static List<Vector3f> path(Vector3f start, Vector3f end, RandomSource random) {
        Vector3f direction = new Vector3f(end).sub(start);
        float length = direction.length();
        List<Vector3f> points = new ArrayList<>();
        points.add(start);
        if (length < 1.0E-4F) {
            points.add(end);
            return points;
        }
        direction.div(length);

        Vector3f reference = Math.abs(direction.y()) > 0.99F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f right = new Vector3f(direction).cross(reference).normalize();
        Vector3f up = new Vector3f(direction).cross(right).normalize();

        int segments = Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, Math.round(length * SEGMENTS_PER_BLOCK)));
        for (int i = 1; i < segments; i++) {
            float t = i / (float) segments;
            float envelope = (float) Math.sin(Math.PI * t);
            float rightOffset = (random.nextFloat() - 0.5F) * 2.0F * JITTER * envelope;
            float upOffset = (random.nextFloat() - 0.5F) * 2.0F * JITTER * envelope;
            points.add(new Vector3f(start).lerp(end, t)
                    .add(new Vector3f(right).mul(rightOffset))
                    .add(new Vector3f(up).mul(upOffset)));
        }
        points.add(end);
        return points;
    }

    private static Vector3f center(BlockPos pos) {
        return new Vector3f(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F);
    }
}
