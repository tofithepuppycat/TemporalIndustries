package io.github.tofithepuppycat.temporalindustries.device;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only view of a finished Chrono Recorder recording, parsed once from the item's
 * {@link DataComponents#CUSTOM_DATA} tag (see ChronoRecorderItem) and reused by the Chrono
 * Loop Projector for playback instead of re-reading NBT every tick/frame.
 *
 * Frame positions are stored relative to the position the recording started at, so playback
 * can re-anchor the path to wherever the projector happens to be. Actions (block break/place)
 * are keyed by the same tick indices as frames and anchored the same way, but as whole-block
 * integer offsets rather than continuous ones.
 */
public final class ChronoRecording {

    public record Frame(float x, float y, float z, float yaw, float pitch, boolean crouching) {}

    /**
     * INSERT/EXTRACT cover any container-capable block (furnace, chest, hopper, brewing stand,
     * ...): the player handing something to it or taking something out of it via its GUI, detected
     * as an inventory diff across the open/close of that container's menu (see ChronoActionRecorder) —
     * not tied to any particular slot, just "this item, this many, went in/came out".
     * <p>
     * MODIFY covers right-clicking a block with an item that changes ITS state in place without
     * placing a new block or opening a container — axe-stripping logs, hoe-tilling dirt,
     * shovel-pathing, honeycomb-waxing, applying a Create casing to a shaft, and anything similar
     * from any mod. Detected generically as a before/after blockstate diff (see ChronoActionRecorder)
     * rather than special-cased per interaction, so it isn't limited to vanilla tool abilities.
     * <p>
     * ATTACK covers the player melee-damaging a living, non-player entity. The exact final damage
     * dealt (post armor/enchantments/criticals) is recorded and reapplied directly on replay rather
     * than recomputed, matching how PLACE/MODIFY reapply their recorded end state instead of
     * recomputing it.
     */
    public enum ActionType { BREAK, PLACE, INSERT, EXTRACT, MODIFY, ATTACK }

    /** Flat energy the Chrono Loop Projector charges every tick just to keep a loop running, before
     * any recorded actions on top. */
    public static final int ENERGY_PER_TICK = 4;

    /** Energy charged by the Chrono Loop Projector for replaying one action of this type, on top of
     * {@link #ENERGY_PER_TICK} — see {@link #averageEnergyPerTick()}. ATTACK is priced highest since
     * it's the only action type that harms mobs rather than just moving blocks/items around. Lives
     * here rather than on the projector so recordings can report their own cost (e.g. in a tooltip)
     * without needing a live projector instance. */
    public static int actionEnergyCost(ActionType type) {
        return switch (type) {
            case ATTACK -> 15;
            case BREAK, PLACE, MODIFY -> 6;
            case INSERT, EXTRACT -> 3;
        };
    }

    /** Hard ceiling on what any single tick can cost, regardless of how many actions land on it —
     * without this, a tick recording something like a sweeping-edge swing through a whole mob farm
     * could demand hundreds of FE in one instant and stall the loop indefinitely even with a
     * healthy average power supply. See {@link #energyCostAt(int)}. */
    public static final int MAX_ENERGY_PER_TICK = 200;

    /**
     * {@code item} is the block placed (PLACE) or the item transferred (INSERT/EXTRACT); the item
     * held during the interaction (MODIFY, informational only — never consumed on replay); null for
     * BREAK. {@code count} is only meaningful for INSERT/EXTRACT (always 1 for PLACE/MODIFY).
     * {@code blockState}/{@code blockEntity} (PLACE and MODIFY) are the exact resulting state and,
     * if any, block entity data — not just the block's default state — so oriented/configured
     * modded blocks (machines, pipes, ...) reproduce faithfully rather than just "some instance of
     * this block". {@code tool} (BREAK only) is the item the player was holding when they broke the
     * block, replayed as a phantom tool purely so the drop calculation respects "requires correct
     * tool" blocks — it's never pulled from the projector's inventory. {@code targetEntityType},
     * {@code targetBaby} and {@code damage} (ATTACK only) are the entity type attacked, whether that
     * entity was a baby, and the exact final damage dealt to it; replay looks for the nearest living
     * entity of that type (and baby/adult state) near the recorded position rather than tracking the
     * original entity's identity, since it may not even be the same instance by the time the loop
     * replays.
     */
    public record Action(int dx, int dy, int dz, ActionType type, @Nullable ResourceLocation item, int count,
                          @Nullable CompoundTag blockState, @Nullable CompoundTag blockEntity, @Nullable ResourceLocation tool,
                          @Nullable ResourceLocation targetEntityType, boolean targetBaby, float damage) {}

    private final UUID ownerId;
    private final String ownerName;
    private final double startX;
    private final double startY;
    private final double startZ;
    @Nullable private final ResourceLocation dimension;
    private final List<Frame> frames;
    private final float[] cumulativeDistance;
    private final Map<Integer, List<Action>> actionsByTick;

    private ChronoRecording(UUID ownerId, String ownerName, double startX, double startY, double startZ,
                             @Nullable ResourceLocation dimension, List<Frame> frames, Map<Integer, List<Action>> actionsByTick) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.dimension = dimension;
        this.frames = frames;
        this.cumulativeDistance = buildCumulativeDistance(frames);
        this.actionsByTick = actionsByTick;
    }

    private static float[] buildCumulativeDistance(List<Frame> frames) {
        float[] result = new float[frames.size()];
        float total = 0F;
        for (int i = 1; i < frames.size(); i++) {
            Frame prev = frames.get(i - 1);
            Frame cur = frames.get(i);
            float dx = cur.x() - prev.x();
            float dz = cur.z() - prev.z();
            total += (float) Math.sqrt(dx * dx + dz * dz);
            result[i] = total;
        }
        return result;
    }

    public UUID getOwnerId() { return ownerId; }
    public String getOwnerName() { return ownerName; }
    public double getStartX() { return startX; }
    public double getStartY() { return startY; }
    public double getStartZ() { return startZ; }
    @Nullable public ResourceLocation getDimension() { return dimension; }
    public int frameCount() { return frames.size(); }
    public double durationSeconds() { return frames.size() / 20.0; }

    /** Wraps around the loop; negative ticks wrap backwards correctly too. */
    public Frame frameAt(int tick) {
        int size = frames.size();
        return frames.get(((tick % size) + size) % size);
    }

    /** Cumulative horizontal distance travelled up to fractional loop-tick t, for driving a walk
     * animation. Has a harmless small discontinuity right at the loop seam. */
    public float limbSwingAt(double t) {
        int size = frames.size();
        int a = (((int) Math.floor(t)) % size + size) % size;
        int b = (a + 1) % size;
        float frac = (float) (t - Math.floor(t));
        return cumulativeDistance[a] + frac * (cumulativeDistance[b] - cumulativeDistance[a]);
    }

    public float limbSwingAmountAt(double t) {
        int size = frames.size();
        int a = (((int) Math.floor(t)) % size + size) % size;
        int b = (a + 1) % size;
        float perTickDistance = cumulativeDistance[b] - cumulativeDistance[a];
        return Math.max(0F, Math.min(1F, Math.abs(perTickDistance) * 4F));
    }

    /** Every action scheduled at loop-tick {@code tick} (empty list if none). */
    public List<Action> actionsAt(int tick) {
        return actionsByTick.getOrDefault(tick, Collections.emptyList());
    }

    /** What the Chrono Loop Projector actually charges for replaying loop-tick {@code tick}:
     * {@link #ENERGY_PER_TICK} plus that tick's recorded actions, clamped to
     * {@link #MAX_ENERGY_PER_TICK}. The single source of truth for per-tick cost, used both by
     * {@code ChronoProjectorBlockEntity.tick} to charge energy and by {@link #averageEnergyPerTick()}
     * to report it, so the two can never drift apart. */
    public int energyCostAt(int tick) {
        int actionsCost = 0;
        for (Action action : actionsAt(tick)) {
            actionsCost += actionEnergyCost(action.type());
        }
        return Math.min(ENERGY_PER_TICK + actionsCost, MAX_ENERGY_PER_TICK);
    }

    /** This recording's total per-tick cost (see {@link #energyCostAt(int)}) spread evenly across
     * the loop's length — i.e. what the Chrono Loop Projector spends per tick on average while
     * replaying it, matching how {@code ChronoProjectorBlockEntity.tick} actually charges energy
     * tick-by-tick (a burst of several actions on one tick, then none for a while). */
    public double averageEnergyPerTick() {
        if (frames.isEmpty()) return 0.0;

        long total = 0;
        for (int tick : actionsByTick.keySet()) {
            total += energyCostAt(tick);
        }
        int ticksWithoutActions = frames.size() - actionsByTick.size();
        total += (long) ticksWithoutActions * ENERGY_PER_TICK;
        return (double) total / frames.size();
    }

    /** The single most expensive tick in this recording (see {@link #energyCostAt(int)}) — e.g. the
     * tick a sweeping-edge swing hit a crowd of mobs. Already clamped to
     * {@link #MAX_ENERGY_PER_TICK}, so this is also the most the projector will ever demand in one
     * instant while replaying it. */
    public int peakEnergyPerTick() {
        int peak = ENERGY_PER_TICK;
        for (int tick : actionsByTick.keySet()) {
            peak = Math.max(peak, energyCostAt(tick));
        }
        return peak;
    }

    public static boolean hasSavedRecording(ItemStack stack) {
        return fromStack(stack).isPresent();
    }

    public static Optional<ChronoRecording> fromStack(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data.isEmpty()) return Optional.empty();

        CompoundTag tag = data.copyTag();
        if (!tag.getBoolean("Saved")) return Optional.empty();

        ListTag frameList = tag.getList("Frames", Tag.TAG_COMPOUND);
        if (frameList.size() < 2) return Optional.empty();

        List<Frame> frames = new ArrayList<>(frameList.size());
        for (int i = 0; i < frameList.size(); i++) {
            CompoundTag f = frameList.getCompound(i);
            frames.add(new Frame(f.getFloat("X"), f.getFloat("Y"), f.getFloat("Z"),
                    f.getFloat("Yaw"), f.getFloat("Pitch"), f.getBoolean("Crouch")));
        }

        Map<Integer, List<Action>> actionsByTick = new java.util.HashMap<>();
        ListTag actionList = tag.getList("Actions", Tag.TAG_COMPOUND);
        for (int i = 0; i < actionList.size(); i++) {
            CompoundTag a = actionList.getCompound(i);
            int tick = a.getInt("Tick");
            ActionType type = ActionType.values()[a.getInt("Type")];
            ResourceLocation item = a.contains("Item") ? ResourceLocation.tryParse(a.getString("Item")) : null;
            int count = a.contains("Count") ? a.getInt("Count") : 1;
            CompoundTag blockState = a.contains("BlockState") ? a.getCompound("BlockState") : null;
            CompoundTag blockEntity = a.contains("BlockEntity") ? a.getCompound("BlockEntity") : null;
            ResourceLocation tool = a.contains("Tool") ? ResourceLocation.tryParse(a.getString("Tool")) : null;
            ResourceLocation targetEntityType = a.contains("TargetType") ? ResourceLocation.tryParse(a.getString("TargetType")) : null;
            boolean targetBaby = a.getBoolean("TargetBaby");
            float damage = a.getFloat("Damage");
            Action action = new Action(a.getInt("DX"), a.getInt("DY"), a.getInt("DZ"), type, item, count,
                    blockState, blockEntity, tool, targetEntityType, targetBaby, damage);
            actionsByTick.computeIfAbsent(tick, k -> new ArrayList<>()).add(action);
        }

        UUID ownerId = tag.hasUUID("OwnerId") ? tag.getUUID("OwnerId") : null;
        String ownerName = tag.getString("OwnerName");
        double startX = tag.getDouble("StartX");
        double startY = tag.getDouble("StartY");
        double startZ = tag.getDouble("StartZ");
        ResourceLocation dimension = tag.contains("StartDimension")
                ? ResourceLocation.tryParse(tag.getString("StartDimension")) : null;

        return Optional.of(new ChronoRecording(ownerId, ownerName, startX, startY, startZ, dimension, frames, actionsByTick));
    }
}
