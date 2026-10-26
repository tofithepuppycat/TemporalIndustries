package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.BoxEdgeParticles;
import io.github.tofithepuppycat.temporalindustries.block.MachineFrame;
import io.github.tofithepuppycat.temporalindustries.block.WithergenicReactor;
import io.github.tofithepuppycat.temporalindustries.block.WithergenicReactorStructure;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.WithergenicReactorMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Multiblock Chaos generator powered by a captured Wither. Every unit of Chaos it produces drags
 * its internal Order/Chaos balance toward Chaos, and output efficiency falls away as the balance
 * leaves the centre, so liquid Order has to be fed in through any face of the structure to keep it
 * running. The Chaos itself accumulates in the {@link ReactorOutputHatchBlockEntity}.
 */
@SuppressWarnings("null")
public class WithergenicReactorBlockEntity extends BlockEntity implements MenuProvider, EntropyInfoProvider, MachineFrameController {
    /** Chaos produced per tick at perfect balance. */
    public static final int BASE_OUTPUT_PER_TICK = 20;
    /** Balance units (of {@link #ENTROPY_MAX}) pushed toward Chaos per mB of Chaos produced. */
    public static final double DRIFT_PER_MB = 0.25;
    /** Efficiency below which the reactor shuts down instead of trickling. */
    public static final double MIN_EFFICIENCY = 0.05;

    public static final int ENTROPY_MAX = AbstractTimelineMachineBlockEntity.ENTROPY_MAX;
    private static final int ENTROPY_BALANCED = ENTROPY_MAX / 2;

    private static final int STRUCTURE_RECHECK_INTERVAL = 20;
    private static final int SYNC_INTERVAL_TICKS = 10;
    private static final double FORMED_EDGE_PARTICLE_SPACING = 0.3;
    private static final DustParticleOptions FORMED_PARTICLE = buildFormedParticle();

    /** Reasons the reactor is not producing, synced to the menu as an int. */
    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_NO_WITHER = 1;
    public static final int STATUS_NO_HATCH = 2;
    public static final int STATUS_BUFFER_FULL = 3;
    public static final int STATUS_UNSTABLE = 4;

    private final FluidTank orderTank = new FluidTank(ENTROPY_MAX) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.ORDER_FLUID.get());
        }
    };
    private final FluidTank chaosTank = new FluidTank(ENTROPY_MAX) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.CHAOS_FLUID.get());
        }
    };

    /**
     * Accepts liquid Order only, which displaces the same amount of Chaos from the internal
     * balance. Nothing can be drained back out.
     */
    private final IFluidHandler orderInput = new IFluidHandler() {
        @Override public int getTanks() { return 1; }
        @Override public @NotNull FluidStack getFluidInTank(int tank) { return orderTank.getFluid(); }
        @Override public int getTankCapacity(int tank) { return orderTank.getCapacity(); }
        @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) { return orderTank.isFluidValid(stack); }

        @Override public int fill(@NotNull FluidStack resource, @NotNull FluidAction action) {
            if (!orderTank.isFluidValid(resource)) return 0;
            int accepted = Math.min(resource.getAmount(), chaosTank.getFluidAmount());
            if (accepted <= 0) return 0;
            int filled = orderTank.fill(new FluidStack(resource.getFluid(), accepted), action);
            if (filled > 0 && action.execute()) {
                chaosTank.drain(filled, FluidAction.EXECUTE);
                setChanged();
            }
            return filled;
        }

        @Override public @NotNull FluidStack drain(@NotNull FluidStack resource, @NotNull FluidAction action) { return FluidStack.EMPTY; }
        @Override public @NotNull FluidStack drain(int maxDrain, @NotNull FluidAction action) { return FluidStack.EMPTY; }
    };

    private final ContainerData menuData = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case 0 -> chaosTank.getFluidAmount();
                case 1 -> (int) Math.round(getEfficiency() * 100.0);
                case 2 -> hatchAmount();
                case 3 -> status;
                case 4 -> outputPerTick;
                default -> 0;
            };
        }

        @Override public void set(int index, int value) {}

        @Override public int getCount() { return 5; }
    };

    @Nullable private ResourceLocation witherTypeId;
    @Nullable private Component witherName;
    private boolean formed = false;
    private int ticksSinceStructureCheck = 0;
    private int status = STATUS_NO_WITHER;
    private int outputPerTick = 0;
    private double outputCarry = 0.0;
    private double driftCarry = 0.0;
    private int lastSyncedChaos = -1;
    private int lastSyncedStatus = -1;

    public WithergenicReactorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.WITHERGENIC_REACTOR_BLOCK_ENTITY.get(), pos, state);
        orderTank.setFluid(new FluidStack(Registration.ORDER_FLUID.get(), ENTROPY_BALANCED));
        chaosTank.setFluid(new FluidStack(Registration.CHAOS_FLUID.get(), ENTROPY_BALANCED));
    }

    private static DustParticleOptions buildFormedParticle() {
        int color = EntropyType.CHAOS.color();
        return new DustParticleOptions(new Vector3f(((color >> 16) & 0xFF) / 255F, ((color >> 8) & 0xFF) / 255F, (color & 0xFF) / 255F), 2.0F);
    }

    // --- Capture ---

    public boolean isOccupied() {
        return witherTypeId != null;
    }

    public void capture(ResourceLocation typeId, @Nullable Component name) {
        this.witherTypeId = typeId;
        this.witherName = name;
        setChanged();
        syncToClients();
    }

    /** Spawns the stored Wither back into the world above this block and clears the capture. */
    public void release() {
        if (level == null || level.isClientSide || witherTypeId == null) return;

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(witherTypeId);
        Entity entity = type.create(level);
        if (entity != null) {
            entity.moveTo(worldPosition.getX() + 0.5, worldPosition.getY() + 0.2, worldPosition.getZ() + 0.5, 0.0F, 0.0F);
            if (witherName != null) entity.setCustomName(witherName);
            level.addFreshEntity(entity);
        }
        witherTypeId = null;
        witherName = null;
        setChanged();
        syncToClients();
    }

    // --- Entropy balance ---

    public int getChaosAmount() {
        return chaosTank.getFluidAmount();
    }

    /** -1 (full Order) .. +1 (full Chaos); 0 is perfectly balanced. */
    public double getBalance() {
        return (chaosTank.getFluidAmount() - ENTROPY_BALANCED) / (double) ENTROPY_BALANCED;
    }

    /** 1 at perfect balance, falling linearly to 0 at either extreme. */
    public double getEfficiency() {
        return Math.max(0.0, 1.0 - Math.abs(getBalance()));
    }

    private void shiftTowardChaos(int units) {
        int moved = Math.min(units, orderTank.getFluidAmount());
        if (moved <= 0) return;
        orderTank.drain(moved, IFluidHandler.FluidAction.EXECUTE);
        chaosTank.fill(new FluidStack(Registration.CHAOS_FLUID.get(), moved), IFluidHandler.FluidAction.EXECUTE);
    }

    // --- Multiblock ---

    @Override
    public IItemHandler getItemHandler() {
        return null;
    }

    @Override
    public IFluidHandler getFluidHandler() {
        return orderInput;
    }

    @Override
    public InteractionResult onFrameInteract(Level level, BlockPos controllerPos, ServerPlayer player) {
        return WithergenicReactor.interact(level, controllerPos, this, player);
    }

    public boolean isFormed() {
        return formed;
    }

    public List<BlockPos> findMissingFrames() {
        List<BlockPos> missing = new ArrayList<>();
        if (level == null) return missing;
        for (BlockPos pos : WithergenicReactorStructure.framePositions(worldPosition, getBlockState().getValue(WithergenicReactor.FACING))) {
            if (level.getBlockEntity(pos) instanceof MachineFrameBlockEntity frameBe) {
                frameBe.setController(worldPosition);
            } else if (!level.getBlockState(pos).is(Registration.MACHINE_FRAME_BLOCK.get())) {
                missing.add(pos);
            }
        }
        return missing;
    }

    public BlockPos hatchPosition() {
        return WithergenicReactorStructure.hatchPosition(worldPosition, getBlockState().getValue(WithergenicReactor.FACING));
    }

    @Nullable
    private ReactorOutputHatchBlockEntity findHatch() {
        if (level == null) return null;
        return level.getBlockEntity(hatchPosition()) instanceof ReactorOutputHatchBlockEntity hatch ? hatch : null;
    }

    public boolean hasHatch() {
        return findHatch() != null;
    }

    private int hatchAmount() {
        ReactorOutputHatchBlockEntity hatch = findHatch();
        return hatch == null ? 0 : hatch.getAmount();
    }

    /** Re-scans the shell, updating {@link #formed} and the controller's block state. */
    public boolean checkStructure() {
        boolean wasFormed = formed;
        formed = findMissingFrames().isEmpty() && hasHatch();
        if (formed != wasFormed) {
            setChanged();
            syncToClients();
            if (level != null && !level.isClientSide) {
                BlockState state = getBlockState();
                if (state.hasProperty(WithergenicReactor.FORMED)) {
                    level.setBlock(worldPosition, state.setValue(WithergenicReactor.FORMED, formed), Block.UPDATE_CLIENTS);
                }
                updateFrameConnectivity(formed);
                if (formed && level instanceof ServerLevel serverLevel) spawnFormedParticles(serverLevel);
            }
        }
        return formed;
    }

    private void updateFrameConnectivity(boolean connected) {
        Direction facing = getBlockState().getValue(WithergenicReactor.FACING);
        for (BlockPos pos : WithergenicReactorStructure.framePositions(worldPosition, facing)) {
            MachineFrame.setConnected(level, pos, connected);
        }
    }

    private void spawnFormedParticles(ServerLevel serverLevel) {
        Direction facing = getBlockState().getValue(WithergenicReactor.FACING);
        List<BlockPos> corners = new ArrayList<>(WithergenicReactorStructure.framePositions(worldPosition, facing));
        corners.add(worldPosition);
        corners.add(hatchPosition());

        int minX = corners.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minY = corners.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minZ = corners.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxX = corners.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maxY = corners.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maxZ = corners.stream().mapToInt(BlockPos::getZ).max().orElseThrow();

        for (Vector3f point : BoxEdgeParticles.outline(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1, FORMED_EDGE_PARTICLE_SPACING)) {
            serverLevel.sendParticles(FORMED_PARTICLE, point.x(), point.y(), point.z(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    // --- Ticking ---

    public static void tick(Level level, BlockPos pos, BlockState state, WithergenicReactorBlockEntity be) {
        if (level.isClientSide) return;
        be.processTick((ServerLevel) level);
    }

    private void processTick(ServerLevel serverLevel) {
        if (++ticksSinceStructureCheck >= STRUCTURE_RECHECK_INTERVAL) {
            ticksSinceStructureCheck = 0;
            checkStructure();
        }
        if (!formed) {
            outputPerTick = 0;
            status = hasHatch() ? STATUS_NO_WITHER : STATUS_NO_HATCH;
            return;
        }

        ReactorOutputHatchBlockEntity hatch = findHatch();
        if (hatch == null) {
            status = STATUS_NO_HATCH;
        } else if (!isOccupied()) {
            status = STATUS_NO_WITHER;
        } else if (getEfficiency() < MIN_EFFICIENCY) {
            status = STATUS_UNSTABLE;
        } else if (hatch.freeSpace() <= 0) {
            status = STATUS_BUFFER_FULL;
        } else {
            status = STATUS_RUNNING;
            produce(serverLevel, hatch);
            return;
        }
        outputPerTick = 0;
        syncIfDue(serverLevel);
    }

    private void produce(ServerLevel serverLevel, ReactorOutputHatchBlockEntity hatch) {
        outputCarry += BASE_OUTPUT_PER_TICK * getEfficiency();
        int wanted = (int) outputCarry;
        outputCarry -= wanted;

        int stored = hatch.insertChaos(wanted);
        outputPerTick = stored;
        if (stored > 0) {
            driftCarry += stored * DRIFT_PER_MB;
            int drift = (int) driftCarry;
            driftCarry -= drift;
            shiftTowardChaos(drift);
            setChanged();
        }

        if (serverLevel.getGameTime() % 4 == 0) {
            BlockPos chamber = WithergenicReactorStructure.chamberPosition(worldPosition, getBlockState().getValue(WithergenicReactor.FACING));
            serverLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, chamber.getX() + 0.5, chamber.getY() + 0.5, chamber.getZ() + 0.5,
                    2, 0.3, 0.3, 0.3, 0.01);
        }
        syncIfDue(serverLevel);
    }

    /** Pushes the balance to clients on a slow cadence so the Entropy Glasses overlay stays current. */
    private void syncIfDue(ServerLevel serverLevel) {
        if (serverLevel.getGameTime() % SYNC_INTERVAL_TICKS != 0) return;
        if (lastSyncedChaos == chaosTank.getFluidAmount() && lastSyncedStatus == status) return;
        lastSyncedChaos = chaosTank.getFluidAmount();
        lastSyncedStatus = status;
        syncToClients();
    }

    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // --- Info and menu ---

    @Override
    public List<Component> getEntropyTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(getDisplayName().copy().withStyle(ChatFormatting.WHITE));
        lines.add(isOccupied()
                ? Component.translatable("overlay.temporalindustries.entropy_glasses.withergenic_reactor.occupied").withStyle(ChatFormatting.DARK_PURPLE)
                : Component.translatable("overlay.temporalindustries.entropy_glasses.withergenic_reactor.empty").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.withergenic_reactor.efficiency",
                (int) Math.round(getEfficiency() * 100.0)).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.withergenic_reactor.status." + status)
                .withStyle(status == STATUS_RUNNING ? ChatFormatting.YELLOW : ChatFormatting.RED));
        return lines;
    }

    @Override public boolean hasEntropyBalance() { return true; }
    @Override public int getEntropyBalance() { return chaosTank.getFluidAmount(); }
    @Override public int getEntropyBalanceMax() { return ENTROPY_MAX; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.temporalindustries.withergenic_reactor");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new WithergenicReactorMenu(id, inventory, this, menuData);
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
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        if (witherTypeId != null) tag.putString("CapturedType", witherTypeId.toString());
        if (witherName != null) tag.putString("CapturedName", Component.Serializer.toJson(witherName, registries));
        tag.putBoolean("Formed", formed);
        tag.putInt("Status", status);
        tag.putDouble("OutputCarry", outputCarry);
        tag.putDouble("DriftCarry", driftCarry);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("OrderTank") && tag.contains("ChaosTank")) {
            orderTank.readFromNBT(registries, tag.getCompound("OrderTank"));
            chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        }
        witherTypeId = tag.contains("CapturedType") ? ResourceLocation.parse(tag.getString("CapturedType")) : null;
        witherName = tag.contains("CapturedName") ? Component.Serializer.fromJson(tag.getString("CapturedName"), registries) : null;
        formed = tag.getBoolean("Formed");
        status = tag.getInt("Status");
        outputCarry = tag.getDouble("OutputCarry");
        driftCarry = tag.getDouble("DriftCarry");
    }
}
