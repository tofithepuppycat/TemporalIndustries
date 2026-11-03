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
 * Draws the lightning that strikes along a pylon's links while it transfers, shaded by {@link
 * LightningShader}. While the server keeps a link refreshed, the link strikes now and then: each
 * strike grows from the source to the target as a stepped leader, then flashes as a return stroke,
 * sometimes twice, and fades away. Its channel is a midpoint-displaced fractal path with a few
 * forks branching off it, and a new strike takes a new shape.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class PylonBoltRenderer {
    /** Ticks a link keeps striking after the server last refreshed it. */
    private static final int ACTIVE_TICKS = 6;
    /** Ticks a single strike stays visible. */
    private static final float STRIKE_TICKS = 6.0F;
    /** Ticks the leader takes to reach the target. */
    private static final float LEADER_TICKS = 0.8F;
    /** Quiet ticks between the start of one strike and the earliest start of the next, and the random extra on top. */
    private static final int MIN_GAP_TICKS = 11;
    private static final int GAP_VARIANCE_TICKS = 10;

    private static final double MAIN_HALF_WIDTH = 0.14D;
    private static final double BRANCH_HALF_WIDTH = 0.07D;
    /** Segment length below which the channel stops being subdivided, in blocks. */
    private static final float MIN_SEGMENT = 0.45F;
    /** How far the first split pushes the channel sideways, as a share of the link length, and its cap in blocks. */
    private static final float DISPLACEMENT_SHARE = 0.12F;
    private static final float MAX_DISPLACEMENT = 1.4F;
    /** How much of the displacement each further split keeps. */
    private static final float ROUGHNESS = 0.55F;
    private static final int MAX_BRANCHES = 4;

    private record Branch(List<Vector3f> points, float startFraction) {}

    private static final class Bolt {
        final BlockPos from;
        final BlockPos to;
        final int color;
        long activeUntil;
        float nextStrike;
        float strikeStart = -1000.0F;
        boolean doubleStroke;
        List<Vector3f> channel = List.of();
        List<Branch> branches = List.of();

        Bolt(BlockPos from, BlockPos to, int color) {
            this.from = from;
            this.to = to;
            this.color = color;
        }
    }

    private static final Map<Long, Bolt> BOLTS = new HashMap<>();

    private PylonBoltRenderer() {}

    /** Starts or refreshes the bolt of {@code color} from {@code from} to {@code to}; a link carrying two colours shows both. */
    public static void add(BlockPos from, BlockPos to, int color) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        long key = (from.asLong() * 31L + to.asLong()) * 31L + color;
        Bolt bolt = BOLTS.computeIfAbsent(key, k -> {
            Bolt created = new Bolt(from.immutable(), to.immutable(), color);
            created.nextStrike = level.getGameTime();
            return created;
        });
        bolt.activeUntil = level.getGameTime() + ACTIVE_TICKS;
    }

    public static void clear() {
        BOLTS.clear();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || BOLTS.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || LightningShader.get() == null) {
            BOLTS.clear();
            return;
        }

        float now = level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        RandomSource random = level.getRandom();

        PoseStack poseStack = event.getPoseStack();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;
        Matrix4f matrix = poseStack.last().pose();

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(LightningShader.renderType());

        for (Iterator<Bolt> it = BOLTS.values().iterator(); it.hasNext(); ) {
            Bolt bolt = it.next();
            boolean active = level.getGameTime() <= bolt.activeUntil;
            float age = now - bolt.strikeStart;

            if (active && now >= bolt.nextStrike) {
                strike(bolt, now, random);
                age = 0.0F;
            }
            if (age > STRIKE_TICKS) {
                if (!active) it.remove();
                continue;
            }
            drawStrike(matrix, buffer, bolt, age, camX, camY, camZ);
        }

        bufferSource.endBatch(LightningShader.renderType());
    }

    /** Begins a new strike: a fresh channel and forks, and when the next one may follow. */
    private static void strike(Bolt bolt, float now, RandomSource random) {
        Vector3f start = center(bolt.from);
        Vector3f end = center(bolt.to);
        RandomSource shape = RandomSource.create(random.nextLong());

        bolt.strikeStart = now;
        bolt.nextStrike = now + MIN_GAP_TICKS + random.nextInt(GAP_VARIANCE_TICKS + 1);
        bolt.doubleStroke = random.nextInt(3) != 0;
        bolt.channel = channel(start, end, shape);
        bolt.branches = branches(bolt.channel, shape);
    }

    private static void drawStrike(Matrix4f matrix, VertexConsumer buffer, Bolt bolt, float age,
                                   double camX, double camY, double camZ) {
        float reveal = Math.min(1.0F, age / LEADER_TICKS);
        float brightness = brightness(age, bolt.doubleStroke);
        if (brightness <= 0.01F) return;

        float r = ((bolt.color >> 16) & 0xFF) / 255.0F;
        float g = ((bolt.color >> 8) & 0xFF) / 255.0F;
        float b = (bolt.color & 0xFF) / 255.0F;

        drawChannel(matrix, buffer, bolt.channel, MAIN_HALF_WIDTH, reveal, false, r, g, b, brightness, camX, camY, camZ);
        for (Branch branch : bolt.branches) {
            if (reveal <= branch.startFraction()) continue;
            float branchReveal = Math.min(1.0F, (reveal - branch.startFraction()) * 4.0F);
            drawChannel(matrix, buffer, branch.points(), BRANCH_HALF_WIDTH, branchReveal, true, r, g, b, brightness * 0.75F, camX, camY, camZ);
        }
    }

    /** The strike's brightness over its life: a faint leader, a sharp return stroke that decays, and optionally a second stroke. */
    private static float brightness(float age, boolean doubleStroke) {
        float value;
        if (age < LEADER_TICKS) {
            value = 0.35F + 0.25F * (age / LEADER_TICKS);
        } else {
            float since = age - LEADER_TICKS;
            value = (float) Math.exp(-since * 0.7D);
            if (doubleStroke) {
                float second = since - 1.6F;
                value += 0.7F * (float) Math.exp(-second * second * 1.2D) * (since > 0.6F ? 1.0F : 0.0F);
            }
        }
        return Math.min(1.0F, value);
    }

    /**
     * Draws a polyline as camera-facing ribbons, showing only the first {@code reveal} of its
     * length. A fork fades along its length towards its tip; the main channel instead fades at both
     * of its ends, in the shader.
     */
    private static void drawChannel(Matrix4f matrix, VertexConsumer buffer, List<Vector3f> path, double halfWidth,
                                    float reveal, boolean fork, float r, float g, float b, float alpha,
                                    double camX, double camY, double camZ) {
        int n = path.size();
        if (n < 2) return;
        float[] along = new float[n];
        for (int i = 1; i < n; i++) {
            along[i] = along[i - 1] + path.get(i).distance(path.get(i - 1));
        }
        float total = along[n - 1];
        if (total < 1.0E-4F) return;
        float limit = total * reveal;

        for (int i = 0; i + 1 < n; i++) {
            if (along[i] >= limit) break;
            Vector3f p0 = path.get(i);
            Vector3f p1 = path.get(i + 1);
            float end = Math.min(along[i + 1], limit);
            float t1 = (end - along[i]) / Math.max(1.0E-4F, along[i + 1] - along[i]);
            double ax = p0.x() - camX, ay = p0.y() - camY, az = p0.z() - camZ;
            double bx = p0.x() + (p1.x() - p0.x()) * t1 - camX;
            double by = p0.y() + (p1.y() - p0.y()) * t1 - camY;
            double bz = p0.z() + (p1.z() - p0.z()) * t1 - camZ;
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            // The ribbon's width direction is perpendicular to both the segment and the view of its midpoint.
            double vx = (ax + bx) / 2, vy = (ay + by) / 2, vz = (az + bz) / 2;
            double sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;
            double sideLength = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sideLength < 1.0E-6D) continue;
            double scale = halfWidth / sideLength;
            sx *= scale;
            sy *= scale;
            sz *= scale;

            float u0 = fork ? 0.5F : along[i] / total;
            float u1 = fork ? 0.5F : end / total;
            float a0 = fork ? alpha * (1.0F - along[i] / total) : alpha;
            float a1 = fork ? alpha * (1.0F - end / total) : alpha;
            buffer.addVertex(matrix, (float) (ax - sx), (float) (ay - sy), (float) (az - sz)).setColor(r, g, b, a0).setUv(u0, 0.0F);
            buffer.addVertex(matrix, (float) (ax + sx), (float) (ay + sy), (float) (az + sz)).setColor(r, g, b, a0).setUv(u0, 1.0F);
            buffer.addVertex(matrix, (float) (bx + sx), (float) (by + sy), (float) (bz + sz)).setColor(r, g, b, a1).setUv(u1, 1.0F);
            buffer.addVertex(matrix, (float) (bx - sx), (float) (by - sy), (float) (bz - sz)).setColor(r, g, b, a1).setUv(u1, 0.0F);
        }
    }

    /** The main channel: the straight line between the ends, split at its middle again and again with the middle pushed sideways. */
    private static List<Vector3f> channel(Vector3f start, Vector3f end, RandomSource random) {
        float length = start.distance(end);
        List<Vector3f> points = new ArrayList<>();
        points.add(start);
        if (length < 1.0E-3F) {
            points.add(end);
            return points;
        }
        int depth = Math.max(3, Math.min(8, (int) Math.ceil(Math.log(length / MIN_SEGMENT) / Math.log(2.0))));
        subdivide(points, start, end, Math.min(length * DISPLACEMENT_SHARE, MAX_DISPLACEMENT), depth, random);
        return points;
    }

    private static void subdivide(List<Vector3f> out, Vector3f a, Vector3f b, float displacement, int depth, RandomSource random) {
        if (depth == 0) {
            out.add(b);
            return;
        }
        Vector3f mid = new Vector3f(a).add(b).mul(0.5F);
        Vector3f direction = new Vector3f(b).sub(a);
        if (direction.lengthSquared() > 1.0E-8F) {
            direction.normalize();
            Vector3f sideways = randomPerpendicular(direction, random);
            mid.add(sideways.mul(displacement * (0.35F + 0.65F * random.nextFloat())));
        }
        subdivide(out, a, mid, displacement * ROUGHNESS, depth - 1, random);
        subdivide(out, mid, b, displacement * ROUGHNESS, depth - 1, random);
    }

    private static Vector3f randomPerpendicular(Vector3f direction, RandomSource random) {
        Vector3f v = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
        v.sub(new Vector3f(direction).mul(v.dot(direction)));
        if (v.lengthSquared() < 1.0E-6F) {
            v.set(direction.y(), -direction.x(), 0.0F);
        }
        return v.normalize();
    }

    /** A few forks that leave the main channel at an angle, each its own small displaced path. */
    private static List<Branch> branches(List<Vector3f> channel, RandomSource random) {
        int n = channel.size();
        if (n < 8) return List.of();
        float total = 0.0F;
        for (int i = 1; i < n; i++) total += channel.get(i).distance(channel.get(i - 1));

        int count = Math.max(1, Math.min(MAX_BRANCHES, Math.round(total / 6.0F)));
        List<Branch> result = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int index = (int) (n * (0.12F + 0.76F * random.nextFloat()));
            index = Math.max(1, Math.min(n - 2, index));
            Vector3f origin = channel.get(index);
            Vector3f along = new Vector3f(channel.get(index + 1)).sub(channel.get(index - 1));
            if (along.lengthSquared() < 1.0E-8F) continue;
            along.normalize();

            Vector3f direction = new Vector3f(along).mul(0.55F).add(randomPerpendicular(along, random).mul(0.85F)).normalize();
            float length = Math.min(6.0F, total * (0.1F + 0.14F * random.nextFloat()));
            if (length < 0.8F) continue;
            Vector3f tip = new Vector3f(origin).add(direction.mul(length));
            // Forks sag a little, the way real ones tend to run downward.
            tip.y -= length * 0.15F;

            List<Vector3f> points = new ArrayList<>();
            points.add(origin);
            subdivide(points, origin, tip, length * 0.18F, 4, random);

            float fraction = 0.0F;
            for (int i = 1; i <= index; i++) fraction += channel.get(i).distance(channel.get(i - 1));
            result.add(new Branch(points, fraction / total));
        }
        return result;
    }

    private static Vector3f center(BlockPos pos) {
        return new Vector3f(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F);
    }
}
