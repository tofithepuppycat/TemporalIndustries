package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Holds the Chaos a {@link WithergenicReactorBlockEntity} produces. Outside access is drain-only,
 * and the hatch pushes into any fluid handler next to it on its own.
 */
@SuppressWarnings("null")
public class ReactorOutputHatchBlockEntity extends BlockEntity implements EntropyInfoProvider {
    /** Kept within a short so the reactor menu can sync the fill level through container data. */
    public static final int BUFFER_CAPACITY = 32_000;
    private static final int PUSH_PER_TICK = 1_000;
    private static final int SYNC_INTERVAL_TICKS = 10;

    private final FluidTank chaosTank = new FluidTank(BUFFER_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.CHAOS_FLUID.get());
        }

        @Override protected void onContentsChanged() {
            setChanged();
        }
    };

    private final IFluidHandler drainOnly = new IFluidHandler() {
        @Override public int getTanks() { return 1; }
        @Override public @NotNull FluidStack getFluidInTank(int tank) { return chaosTank.getFluid(); }
        @Override public int getTankCapacity(int tank) { return chaosTank.getCapacity(); }
        @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) { return false; }
        @Override public int fill(@NotNull FluidStack resource, @NotNull FluidAction action) { return 0; }
        @Override public @NotNull FluidStack drain(@NotNull FluidStack resource, @NotNull FluidAction action) {
            return chaosTank.drain(resource, action);
        }
        @Override public @NotNull FluidStack drain(int maxDrain, @NotNull FluidAction action) {
            return chaosTank.drain(maxDrain, action);
        }
    };

    private int lastSyncedAmount = -1;

    public ReactorOutputHatchBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.REACTOR_OUTPUT_HATCH_BLOCK_ENTITY.get(), pos, state);
    }

    public IFluidHandler getFluidHandler() {
        return drainOnly;
    }

    public int getAmount() {
        return chaosTank.getFluidAmount();
    }

    /** Stores up to {@code millibuckets} of Chaos and returns how much fit. */
    public int insertChaos(int millibuckets) {
        return chaosTank.fill(new FluidStack(Registration.CHAOS_FLUID.get(), millibuckets), IFluidHandler.FluidAction.EXECUTE);
    }

    public int freeSpace() {
        return chaosTank.getCapacity() - chaosTank.getFluidAmount();
    }

    @Override
    public List<Component> getEntropyTooltip() {
        return List.of(
                getBlockState().getBlock().getName().withStyle(ChatFormatting.WHITE),
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                                EntropyDisplay.formatFluid(chaosTank.getFluidAmount()), EntropyDisplay.formatFluid(chaosTank.getCapacity()))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.CHAOS)));
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ReactorOutputHatchBlockEntity be) {
        if (level.isClientSide) return;

        if (be.chaosTank.getFluidAmount() > 0) be.pushToNeighbours(level, pos);

        if (level.getGameTime() % SYNC_INTERVAL_TICKS == 0 && be.lastSyncedAmount != be.chaosTank.getFluidAmount()) {
            be.lastSyncedAmount = be.chaosTank.getFluidAmount();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private void pushToNeighbours(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbourPos = pos.relative(direction);
            // Frames forward to the reactor's input, which refuses Chaos anyway.
            if (level.getBlockEntity(neighbourPos) instanceof MachineFrameBlockEntity) continue;
            IFluidHandler target = level.getCapability(Capabilities.FluidHandler.BLOCK, neighbourPos, direction.getOpposite());
            if (target == null) continue;

            FluidStack offer = chaosTank.drain(PUSH_PER_TICK, IFluidHandler.FluidAction.SIMULATE);
            if (offer.isEmpty()) return;
            int accepted = target.fill(offer, IFluidHandler.FluidAction.EXECUTE);
            if (accepted > 0) chaosTank.drain(accepted, IFluidHandler.FluidAction.EXECUTE);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ChaosTank")) chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
    }
}
