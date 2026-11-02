package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.BoxEdgeParticles;
import io.github.tofithepuppycat.temporalindustries.block.EntropicPylon;
import io.github.tofithepuppycat.temporalindustries.block.MachineFrame;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.item.EntropicPylonItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Transfers liquid Order/Chaos between marked blocks. */
@SuppressWarnings("null")
public class EntropicPylonBlockEntity extends BlockEntity implements MachineFrameController, EntropyInfoProvider, WrenchConfigurable {
    private static final int STRUCTURE_RECHECK_INTERVAL = 20;
    /** Most machine frames below the pylon that count towards its range. */
    public static final int MAX_FRAMES = 8;
    /** mB of whichever fluid is present, moved per input->output pair each tick. */
    private static final int TRANSFER_RATE_MB = 50;

    private static final double FORMED_EDGE_PARTICLE_SPACING = 0.3;
    /**
     * Spark texture so the flow of fluid along a leg reads as distinct from the structure outlines.
     */
    private static final ColorParticleOption ORDER_TRANSMIT_PARTICLE = buildColorParticle(EntropyType.ORDER);
    private static final ColorParticleOption CHAOS_TRANSMIT_PARTICLE = buildColorParticle(EntropyType.CHAOS);
    /** Interior joints in the jagged transmit bolt, not counting its two fixed endpoints. */
    private static final int LIGHTNING_SEGMENTS = 6;
    /** Max perpendicular displacement of an interior joint off the straight line, in blocks. */
    private static final double LIGHTNING_JITTER = 0.25D;
    /** Target distance between consecutive particles along each jagged segment, in blocks. */
    private static final double LIGHTNING_PARTICLE_SPACING = 0.15D;

    private static DustParticleOptions buildParticle(EntropyType type, float scale) {
        int color = type.color();
        float r = ((color >> 16) & 0xFF) / 255F;
        float g = ((color >> 8) & 0xFF) / 255F;
        float b = (color & 0xFF) / 255F;
        return new DustParticleOptions(new Vector3f(r, g, b), scale);
    }

    private static ColorParticleOption buildColorParticle(EntropyType type) {
        int color = type.color();
        float r = ((color >> 16) & 0xFF) / 255F;
        float g = ((color >> 8) & 0xFF) / 255F;
        float b = (color & 0xFF) / 255F;
        return ColorParticleOption.create(Registration.TRANSMIT_SPARK.get(), r, g, b);
    }

    private List<BlockPos> inputs = new ArrayList<>();
    private List<BlockPos> outputs = new ArrayList<>();
    /** Number of machine frames stacked directly below, each of which multiplies the link range. */
    private int frames = 0;
    private int ticksSinceStructureCheck = STRUCTURE_RECHECK_INTERVAL;
    private int outputCursor = 0;

    /**
     * Which entropy type this pylon is restricted to, or {@code null} for the dual pylon that
     * routes whichever is present.
     */
    @Nullable
    private final EntropyType filter;
    private final DustParticleOptions formedParticle;

    public EntropicPylonBlockEntity(BlockPos pos, BlockState state) {
        this(Registration.ENTROPIC_PYLON_BLOCK_ENTITY.get(), pos, state, null);
    }

    public EntropicPylonBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, @Nullable EntropyType filter) {
        super(type, pos, state);
        this.filter = filter;
        this.formedParticle = buildParticle(filter != null ? filter : EntropyType.ORDER, 2.0F);
    }

    public List<BlockPos> getInputs() {
        return inputs;
    }

    public List<BlockPos> getOutputs() {
        return outputs;
    }

    public int getFrames() {
        return frames;
    }

    /** Half-width of the cube around the pylon that links must fall inside. */
    public int getRange() {
        return EntropicPylonItem.RANGE * Math.max(1, frames);
    }

    private boolean inRange(BlockPos target) {
        int range = getRange();
        return Math.abs(target.getX() - worldPosition.getX()) <= range
                && Math.abs(target.getY() - worldPosition.getY()) <= range
                && Math.abs(target.getZ() - worldPosition.getZ()) <= range;
    }

    @Override
    public List<Component> getEntropyTooltip() {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock());
        String prefix = "block." + key.getNamespace() + "." + key.getPath();
        return List.of(
                Component.translatable(prefix).withStyle(ChatFormatting.WHITE),
                Component.translatable(prefix + ".status", inputs.size(), outputs.size(), getRange() * 2 + 1).withStyle(ChatFormatting.GRAY));
    }

    /** Called once, right as the placing item hands off whatever marks are still in range. */
    public void setMarks(List<BlockPos> inputs, List<BlockPos> outputs) {
        this.inputs = new ArrayList<>(inputs);
        this.outputs = new ArrayList<>(outputs);
        setChanged();
        syncToClients();
    }

    @Override
    public Component wrenchLink(BlockPos target, boolean output) {
        String prefix = "item.temporalindustries.wrench.pylon.";
        String role = output ? "output" : "input";
        int range = getRange();
        if (level == null || target.equals(worldPosition)
                || level.getCapability(Capabilities.FluidHandler.BLOCK, target, null) == null) {
            return Component.translatable(prefix + "invalid");
        }
        if (!inRange(target)) {
            return Component.translatable(prefix + "too_far", range);
        }

        List<BlockPos> same = output ? outputs : inputs;
        List<BlockPos> opposite = output ? inputs : outputs;
        Component result;
        if (same.remove(target)) {
            result = Component.translatable(prefix + "removed_" + role, same.size());
        } else {
            opposite.remove(target);
            if (same.size() >= EntropicPylonItem.MAX_MARKS) same.remove(0);
            same.add(target.immutable());
            result = Component.translatable(prefix + "added_" + role, same.size(), EntropicPylonItem.MAX_MARKS);
        }
        setChanged();
        syncToClients();
        return result;
    }

    @Override
    public List<BlockPos> wrenchInputs() {
        return inputs;
    }

    @Override
    public List<BlockPos> wrenchOutputs() {
        return outputs;
    }

    @Override
    public Component wrenchClear() {
        inputs.clear();
        outputs.clear();
        setChanged();
        syncToClients();
        return Component.translatable("item.temporalindustries.wrench.pylon.cleared");
    }

    private MachineFrame.PylonStyle pylonStyle() {
        if (filter == EntropyType.CHAOS) return MachineFrame.PylonStyle.CHAOS;
        if (filter == EntropyType.ORDER) return MachineFrame.PylonStyle.ORDER;
        return MachineFrame.PylonStyle.ENTROPIC;
    }

    /**
     * Re-counts the {@link MachineFrame}s stacked directly below, restyles them as the pylon's
     * column and updates {@link EntropicPylon#EXTENDED} on the pylon; syncs to clients if the
     * count changed. Returns the frame count.
     */
    public int checkStructure() {
        if (level == null) return frames;
        int wasFrames = frames;
        int count = 0;
        BlockPos pos = worldPosition.below();
        while (count < MAX_FRAMES && level.getBlockState(pos).is(Registration.MACHINE_FRAME_BLOCK.get())) {
            count++;
            pos = pos.below();
        }
        frames = count;

        if (!level.isClientSide) {
            for (int i = 1; i <= count; i++) {
                BlockPos framePos = worldPosition.below(i);
                if (level.getBlockEntity(framePos) instanceof MachineFrameBlockEntity frameBe) {
                    frameBe.setController(worldPosition);
                }
                MachineFrame.setPylon(level, framePos, pylonStyle(), i == count);
            }
            BlockState state = getBlockState();
            if (state.hasProperty(EntropicPylon.EXTENDED) && state.getValue(EntropicPylon.EXTENDED) != (count > 0)) {
                level.setBlock(worldPosition, state.setValue(EntropicPylon.EXTENDED, count > 0), Block.UPDATE_CLIENTS);
            }
        }

        if (frames != wasFrames) {
            setChanged();
            syncToClients();
            if (!level.isClientSide && frames > wasFrames && level instanceof ServerLevel serverLevel) {
                spawnFormedParticles(serverLevel);
            }
        }
        return frames;
    }

    /**
     * Re-checks the pylon above {@code from}, if a run of machine frames leads up to one; called when
     * a frame is placed or removed.
     */
    public static void refreshAbove(Level level, BlockPos from) {
        BlockPos pos = from.above();
        for (int i = 0; i < MAX_FRAMES && level.getBlockState(pos).is(Registration.MACHINE_FRAME_BLOCK.get()); i++) {
            pos = pos.above();
        }
        if (level.getBlockEntity(pos) instanceof EntropicPylonBlockEntity be) {
            be.checkStructure();
        }
    }

    /** Outline traced along the edges of the pylon and the frames beneath it. */
    private void spawnFormedParticles(ServerLevel serverLevel) {
        int minY = worldPosition.getY() - frames;
        for (Vector3f point : BoxEdgeParticles.outline(worldPosition.getX(), minY, worldPosition.getZ(),
                worldPosition.getX() + 1, worldPosition.getY() + 1, worldPosition.getZ() + 1, FORMED_EDGE_PARTICLE_SPACING)) {
            serverLevel.sendParticles(formedParticle, point.x(), point.y(), point.z(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, EntropicPylonBlockEntity be) {
        if (level.isClientSide) return;
        be.processTick();
    }

    private void processTick() {
        if (++ticksSinceStructureCheck >= STRUCTURE_RECHECK_INTERVAL) {
            ticksSinceStructureCheck = 0;
            checkStructure();
        }
        if (inputs.isEmpty() || outputs.isEmpty()) return;
        transferOnce();
    }

    /**
     * One transfer attempt per marked input, against the next marked output in rotation, so every
     * pair gets visited over time instead of always favoring the first output.
     */
    private boolean isReachable(BlockPos target) {
        return inRange(target) && level.isLoaded(target);
    }

    private void transferOnce() {
        for (BlockPos inPos : inputs) {
            if (!isReachable(inPos)) continue;
            IFluidHandler source = level.getCapability(Capabilities.FluidHandler.BLOCK, inPos, null);
            if (source == null) continue;

            BlockPos outPos = outputs.get(outputCursor % outputs.size());
            outputCursor++;
            if (!isReachable(outPos)) continue;
            IFluidHandler dest = level.getCapability(Capabilities.FluidHandler.BLOCK, outPos, null);
            if (dest == null) continue;

            transferAnyTank(source, dest, inPos, outPos);
        }
    }

    /**
     * Whether {@code stack} is allowed to move through this pylon: anything if {@link #filter} is
     * {@code null} (dual pylon), otherwise only the matching entropy type.
     */
    private boolean acceptsFluid(FluidStack stack) {
        return filter == null || EntropyFluids.typeOf(stack.getFluid()) == filter;
    }

    // Tries every tank in source individually (rather than FluidUtil.tryFluidTransfer, whose ambiguous
    // drain overload would always resolve dual-fluid handlers to whichever tank they check first).
    private void transferAnyTank(IFluidHandler source, IFluidHandler dest, BlockPos inPos, BlockPos outPos) {
        for (int i = 0; i < source.getTanks(); i++) {
            FluidStack inTank = source.getFluidInTank(i);
            if (inTank.isEmpty() || !acceptsFluid(inTank)) continue;

            FluidStack wanted = inTank.copyWithAmount(Math.min(TRANSFER_RATE_MB, inTank.getAmount()));
            FluidStack simulated = source.drain(wanted, IFluidHandler.FluidAction.SIMULATE);
            if (simulated.isEmpty()) continue;

            int filled = dest.fill(simulated, IFluidHandler.FluidAction.SIMULATE);
            if (filled <= 0) continue;

            FluidStack drained = source.drain(simulated.copyWithAmount(filled), IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) continue;
            dest.fill(drained, IFluidHandler.FluidAction.EXECUTE);

            EntropyType type = EntropyFluids.typeOf(drained.getFluid());
            if (type != null && level instanceof ServerLevel serverLevel) {
                spawnTransmitParticles(serverLevel, type, inPos, worldPosition);
                spawnTransmitParticles(serverLevel, type, worldPosition, outPos);
            }
        }
    }

    /**
     * A jagged bolt from {@code from} to {@code to}, colored by {@code type}, drawn every tick a
     * transfer moves fluid so the flow direction reads at a glance.
     */
    private void spawnTransmitParticles(ServerLevel serverLevel, EntropyType type, BlockPos from, BlockPos to) {
        ColorParticleOption particle = type == EntropyType.ORDER ? ORDER_TRANSMIT_PARTICLE : CHAOS_TRANSMIT_PARTICLE;
        Vector3f start = centerOf(from);
        Vector3f end = centerOf(to);
        for (Vector3f point : lightningArc(start, end, serverLevel.getRandom())) {
            serverLevel.sendParticles(particle, point.x(), point.y(), point.z(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * Builds a jagged path from {@code start} to {@code end}: straight sub-segments with jittered
     * interior joints, densely resampled so the zigzag reads as a continuous bolt.
     */
    private static List<Vector3f> lightningArc(Vector3f start, Vector3f end, RandomSource random) {
        Vector3f direction = new Vector3f(end).sub(start);
        if (direction.lengthSquared() < 1.0E-6F) return List.of(start);
        direction.normalize();

        Vector3f reference = Math.abs(direction.y()) > 0.99F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f right = new Vector3f(direction).cross(reference).normalize();
        Vector3f up = new Vector3f(direction).cross(right).normalize();

        Vector3f[] joints = new Vector3f[LIGHTNING_SEGMENTS + 1];
        joints[0] = start;
        joints[LIGHTNING_SEGMENTS] = end;
        for (int i = 1; i < LIGHTNING_SEGMENTS; i++) {
            float t = i / (float) LIGHTNING_SEGMENTS;
            float rightOffset = (random.nextFloat() - 0.5F) * 2F * (float) LIGHTNING_JITTER;
            float upOffset = (random.nextFloat() - 0.5F) * 2F * (float) LIGHTNING_JITTER;
            joints[i] = new Vector3f(start).lerp(end, t)
                    .add(new Vector3f(right).mul(rightOffset))
                    .add(new Vector3f(up).mul(upOffset));
        }

        List<Vector3f> points = new ArrayList<>();
        for (int i = 0; i < LIGHTNING_SEGMENTS; i++) {
            Vector3f a = joints[i];
            Vector3f b = joints[i + 1];
            double segmentLength = new Vector3f(b).sub(a).length();
            int steps = Math.max(1, (int) Math.round(segmentLength / LIGHTNING_PARTICLE_SPACING));
            for (int s = (i == 0 ? 0 : 1); s <= steps; s++) {
                float t = s / (float) steps;
                points.add(new Vector3f(a).lerp(b, t));
            }
        }
        return points;
    }

    private static Vector3f centerOf(BlockPos pos) {
        return new Vector3f(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F);
    }

    // The pylon isn't itself an item/fluid container, only a router; a click just reports status.
    @Override
    @Nullable
    public IItemHandler getItemHandler() {
        return null;
    }

    @Override
    @Nullable
    public IFluidHandler getFluidHandler() {
        return null;
    }

    @Override
    public InteractionResult onFrameInteract(Level level, BlockPos controllerPos, ServerPlayer player) {
        checkStructure();
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock());
        String prefix = "block." + key.getNamespace() + "." + key.getPath();
        player.displayClientMessage(Component.translatable(prefix + ".status",
                inputs.size(), outputs.size(), getRange() * 2 + 1), true);
        return InteractionResult.CONSUME;
    }

    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inputs", posListTag(inputs));
        tag.put("Outputs", posListTag(outputs));
        tag.putInt("Frames", frames);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inputs = readPosList(tag, "Inputs");
        outputs = readPosList(tag, "Outputs");
        frames = tag.getInt("Frames");
    }

    private static LongArrayTag posListTag(List<BlockPos> list) {
        long[] array = new long[list.size()];
        for (int i = 0; i < list.size(); i++) array[i] = list.get(i).asLong();
        return new LongArrayTag(array);
    }

    private static List<BlockPos> readPosList(CompoundTag tag, String key) {
        List<BlockPos> list = new ArrayList<>();
        if (!tag.contains(key)) return list;
        for (long packed : tag.getLongArray(key)) list.add(BlockPos.of(packed));
        return list;
    }
}
