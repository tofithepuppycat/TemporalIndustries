package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.BoxEdgeParticles;
import io.github.tofithepuppycat.temporalindustries.block.EntropicPylon;
import io.github.tofithepuppycat.temporalindustries.block.MachineFrame;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.item.EntropicPylonItem;
import io.github.tofithepuppycat.temporalindustries.network.PylonBoltPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
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
import net.neoforged.neoforge.network.PacketDistributor;
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
    /** Ticks between refreshes of a link's bolt on clients; the bolt outlasts the gap. */
    private static final int BOLT_INTERVAL_TICKS = 2;

    private static DustParticleOptions buildParticle(EntropyType type, float scale) {
        return buildParticle(type.color(), scale);
    }

    protected static DustParticleOptions buildParticle(int color, float scale) {
        float r = ((color >> 16) & 0xFF) / 255F;
        float g = ((color >> 8) & 0xFF) / 255F;
        float b = (color & 0xFF) / 255F;
        return new DustParticleOptions(new Vector3f(r, g, b), scale);
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
        this(type, pos, state, filter, (filter != null ? filter : EntropyType.ORDER).color());
    }

    protected EntropicPylonBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, @Nullable EntropyType filter,
                                       int formedColor) {
        super(type, pos, state);
        this.filter = filter;
        this.formedParticle = buildParticle(formedColor, 2.0F);
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
        if (level == null || target.equals(worldPosition) || !isLinkable(target)) {
            return Component.translatable(prefix + invalidLinkKey());
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

    /** Whether the block at target holds what this pylon routes. */
    protected boolean isLinkable(BlockPos target) {
        return level.getCapability(Capabilities.FluidHandler.BLOCK, target, null) != null;
    }

    /** Wrench message key, under the pylon prefix, for a link target that holds nothing this pylon routes. */
    protected String invalidLinkKey() {
        return "invalid";
    }

    protected MachineFrame.PylonStyle pylonStyle() {
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
    protected boolean isReachable(BlockPos target) {
        return inRange(target) && level.isLoaded(target);
    }

    /** The next linked output in rotation, or null when none is linked. */
    @Nullable
    protected BlockPos nextOutput() {
        if (outputs.isEmpty()) return null;
        return outputs.get(outputCursor++ % outputs.size());
    }

    protected void transferOnce() {
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
                sendBolt(serverLevel, type.color(), inPos, worldPosition);
                sendBolt(serverLevel, type.color(), worldPosition, outPos);
            }
        }
    }

    /**
     * Tells clients tracking this pylon to show a bolt of {@code color} along the link from
     * {@code from} to {@code to}; sent every few ticks while a transfer keeps moving something.
     */
    protected void sendBolt(ServerLevel serverLevel, int color, BlockPos from, BlockPos to) {
        if (serverLevel.getGameTime() % BOLT_INTERVAL_TICKS != 0) return;
        PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition), new PylonBoltPacket(from, to, color));
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
