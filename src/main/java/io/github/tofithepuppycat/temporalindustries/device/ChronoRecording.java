package io.github.tofithepuppycat.temporalindustries.device;

import net.minecraft.core.Direction;
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
 * Read-only view of a finished Echo Record recording, parsed once from the item's {@link
 * DataComponents#CUSTOM_DATA} tag and reused by the Echo Projector for playback instead of re-
 * reading NBT every tick/frame.
 */
public final class ChronoRecording {

    public record Frame(float x, float y, float z, float yaw, float pitch, boolean crouching) {}

    /**
     * INSERT/EXTRACT: items handed to or taken from a container-capable block, detected as an
     * inventory diff across a container GUI open/close.
     */
    public enum ActionType { BREAK, INSERT, EXTRACT, ATTACK, USE_BLOCK, USE_ITEM, RELEASE_ITEM, USE_ENTITY, MOUNT, DISMOUNT }

    /**
     * Flat energy the Chrono Loop Projector charges every tick just to keep a loop running, before
     * any recorded actions on top.
     */
    public static final int ENERGY_PER_TICK = 15;

    /**
     * Energy charged by the Chrono Loop Projector for replaying one action of this type, on top of
     * {@link #ENERGY_PER_TICK}.
     */
    public static int actionEnergyCost(ActionType type) {
        return switch (type) {
            case ATTACK -> 45;
            case BREAK, USE_BLOCK, USE_ITEM, USE_ENTITY -> 18;
            case INSERT, EXTRACT, RELEASE_ITEM, MOUNT, DISMOUNT -> 9;
        };
    }

    /**
     * Hard ceiling on what any single tick can cost, so a burst of many actions on one tick (e.g. a
     * sweeping-edge hit on a mob farm) can't stall the loop indefinitely.
     */
    public static final int MAX_ENERGY_PER_TICK = 500;

    /** {@code item}/{@code count}: the item transferred (INSERT/EXTRACT only). */
    public record Action(int dx, int dy, int dz, ActionType type, @Nullable ResourceLocation item, int count,
                          @Nullable ResourceLocation tool, @Nullable ResourceLocation targetEntityType,
                          boolean targetBaby, @Nullable Interaction interaction) {}

    /** How the player performed an interaction. */
    public record Interaction(boolean offHand, @Nullable Direction face, float hitX, float hitY, float hitZ,
                               boolean inside, boolean sneaking, boolean specific, float strength,
                               @Nullable CompoundTag heldStack) {
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("Off", offHand);
            tag.putInt("Face", face == null ? -1 : face.get3DDataValue());
            tag.putFloat("HX", hitX);
            tag.putFloat("HY", hitY);
            tag.putFloat("HZ", hitZ);
            tag.putBoolean("Inside", inside);
            tag.putBoolean("Sneak", sneaking);
            tag.putBoolean("Specific", specific);
            tag.putFloat("Strength", strength);
            if (heldStack != null) tag.put("Held", heldStack);
            return tag;
        }

        public static Interaction fromTag(CompoundTag tag) {
            int face = tag.contains("Face") ? tag.getInt("Face") : -1;
            return new Interaction(tag.getBoolean("Off"), face < 0 ? null : Direction.from3DDataValue(face),
                    tag.getFloat("HX"), tag.getFloat("HY"), tag.getFloat("HZ"), tag.getBoolean("Inside"),
                    tag.getBoolean("Sneak"), tag.getBoolean("Specific"), tag.getFloat("Strength"),
                    tag.contains("Held") ? tag.getCompound("Held") : null);
        }
    }

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

    /** Wraps around the loop; negative ticks wrap backwards correctly too. */
    public Frame frameAt(int tick) {
        int size = frames.size();
        return frames.get(((tick % size) + size) % size);
    }

    /**
     * Cumulative horizontal distance travelled up to fractional loop-tick t, for driving a walk
     * animation.
     */
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

    /**
     * What the Chrono Loop Projector actually charges for replaying loop-tick {@code tick}: {@link
     * #ENERGY_PER_TICK} plus that tick's recorded actions, clamped to {@link #MAX_ENERGY_PER_TICK}.
     */
    public int energyCostAt(int tick) {
        int actionsCost = 0;
        for (Action action : actionsAt(tick)) {
            actionsCost += actionEnergyCost(action.type());
        }
        return Math.min(ENERGY_PER_TICK + actionsCost, MAX_ENERGY_PER_TICK);
    }

    /**
     * This recording's total per-tick cost spread evenly across the loop's length, i.e. what the
     * Chrono Loop Projector spends per tick on average while replaying it.
     */
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

    /**
     * The single most expensive tick in this recording, already clamped to {@link
     * #MAX_ENERGY_PER_TICK} — the most the projector will ever demand in one instant.
     */
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
            int typeOrdinal = a.getInt("Type");
            if (typeOrdinal < 0 || typeOrdinal >= ActionType.values().length) continue;
            ActionType type = ActionType.values()[typeOrdinal];
            ResourceLocation item = a.contains("Item") ? ResourceLocation.tryParse(a.getString("Item")) : null;
            int count = a.contains("Count") ? a.getInt("Count") : 1;
            ResourceLocation tool = a.contains("Tool") ? ResourceLocation.tryParse(a.getString("Tool")) : null;
            ResourceLocation targetEntityType = a.contains("TargetType") ? ResourceLocation.tryParse(a.getString("TargetType")) : null;
            boolean targetBaby = a.getBoolean("TargetBaby");
            Interaction interaction = a.contains("Use") ? Interaction.fromTag(a.getCompound("Use")) : null;
            Action action = new Action(a.getInt("DX"), a.getInt("DY"), a.getInt("DZ"), type, item, count,
                    tool, targetEntityType, targetBaby, interaction);
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
