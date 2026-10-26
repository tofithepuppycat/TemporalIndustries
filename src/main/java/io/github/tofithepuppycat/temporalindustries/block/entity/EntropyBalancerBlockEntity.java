package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropyBalancerMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
 * Buffers liquid Order/Chaos and, in small pulses, pushes whichever one the bound output machine
 * needs to bring its entropy balance to the configured target. The target is an offset from the
 * balanced middle in the same 0.01 units the GUIs display, so 0 is perfectly balanced.
 */
@SuppressWarnings("null")
public class EntropyBalancerBlockEntity extends BlockEntity implements MenuProvider, EntropyInfoProvider {
    public static final int TANK_CAPACITY = 8_000;
    /** The target offset is limited to +-MAX_TARGET, i.e. +-50.00 on the displayed scale. */
    public static final int MAX_TARGET = 5_000;
    /** Largest distance, per axis, between the balancer and its output. */
    public static final int BIND_RANGE = 16;

    private static final int PULSE_INTERVAL_TICKS = 5;
    private static final int PULSE_AMOUNT = 100;
    /** Scale of the target and of the offsets reported to the menu, matching ENTROPY_MAX of the machines. */
    private static final int BALANCE_SCALE = 10_000;

    public enum OutputStatus { UNBOUND, ACTIVE, UNREACHABLE }

    private final FluidTank orderTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.ORDER_FLUID.get());
        }

        @Override protected void onContentsChanged() {
            setChanged();
            syncPending = true;
        }
    };
    private final FluidTank chaosTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.CHAOS_FLUID.get());
        }

        @Override protected void onContentsChanged() {
            setChanged();
            syncPending = true;
        }
    };

    /** Accepts Order and Chaos from the outside; the buffers can only be emptied by dosing. */
    private final IFluidHandler fluidHandler = new IFluidHandler() {
        @Override public int getTanks() { return 2; }

        @Override public @NotNull FluidStack getFluidInTank(int tank) {
            return tank == 0 ? orderTank.getFluid() : chaosTank.getFluid();
        }

        @Override public int getTankCapacity(int tank) { return TANK_CAPACITY; }

        @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return tank == 0 ? orderTank.isFluidValid(stack) : chaosTank.isFluidValid(stack);
        }

        @Override public int fill(FluidStack resource, FluidAction action) {
            if (orderTank.isFluidValid(resource)) return orderTank.fill(resource, action);
            if (chaosTank.isFluidValid(resource)) return chaosTank.fill(resource, action);
            return 0;
        }

        @Override public @NotNull FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }

        @Override public @NotNull FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
    };

    /** Offset from the balanced middle the output should reach, in 0.01 display units. */
    private int target = 0;
    @Nullable private BlockPos outputPos;

    private OutputStatus status = OutputStatus.UNBOUND;
    /** The output's current offset from balance on the same scale as {@link #target}. */
    private int outputOffset = 0;
    private boolean syncPending = false;

    public EntropyBalancerBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.ENTROPY_BALANCER_BLOCK_ENTITY.get(), pos, state);
    }

    public IFluidHandler getFluidHandler() { return fluidHandler; }
    public FluidTank getOrderTank() { return orderTank; }
    public FluidTank getChaosTank() { return chaosTank; }
    public int getTarget() { return target; }
    @Nullable public BlockPos getOutputPos() { return outputPos; }
    public OutputStatus getStatus() { return status; }
    public int getOutputOffset() { return outputOffset; }

    public void setTarget(int value) {
        int clamped = Math.clamp(value, -MAX_TARGET, MAX_TARGET);
        if (clamped == target) return;
        target = clamped;
        setChanged();
        syncToClients();
    }

    /** Whether pos is close enough to be this balancer's output. */
    public boolean isInRange(BlockPos pos) {
        return !pos.equals(worldPosition)
                && Math.abs(pos.getX() - worldPosition.getX()) <= BIND_RANGE
                && Math.abs(pos.getY() - worldPosition.getY()) <= BIND_RANGE
                && Math.abs(pos.getZ() - worldPosition.getZ()) <= BIND_RANGE;
    }

    /** Binds pos as the output machine; false if it is out of range. */
    public boolean bindOutput(BlockPos pos) {
        if (!isInRange(pos)) return false;
        outputPos = pos.immutable();
        status = OutputStatus.ACTIVE;
        setChanged();
        syncToClients();
        return true;
    }

    public void clearOutput() {
        if (outputPos == null) return;
        outputPos = null;
        status = OutputStatus.UNBOUND;
        setChanged();
        syncToClients();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, EntropyBalancerBlockEntity be) {
        if (level.isClientSide) return;
        if (level.getGameTime() % PULSE_INTERVAL_TICKS == 0) be.pulse(level);
        if (be.syncPending) {
            be.syncPending = false;
            be.syncToClients();
        }
    }

    /** Refreshes the output's status and doses it one step toward the target. */
    private void pulse(Level level) {
        BlockPos out = outputPos;
        if (out == null) {
            status = OutputStatus.UNBOUND;
            return;
        }

        IFluidHandler handler = isInRange(out) && level.isLoaded(out)
                ? level.getCapability(Capabilities.FluidHandler.BLOCK, out, null) : null;
        if (handler == null || !(level.getBlockEntity(out) instanceof EntropyInfoProvider provider) || !provider.hasEntropyBalance()) {
            status = OutputStatus.UNREACHABLE;
            return;
        }
        status = OutputStatus.ACTIVE;

        int max = provider.getEntropyBalanceMax();
        int current = provider.getEntropyBalance();
        outputOffset = Math.round((current - max / 2.0F) * BALANCE_SCALE / max);

        int delta = Math.round(max / 2.0F + (float) target * max / BALANCE_SCALE) - current;
        if (delta == 0) return;

        EntropyType type = delta > 0 ? EntropyType.CHAOS : EntropyType.ORDER;
        FluidTank tank = type == EntropyType.CHAOS ? chaosTank : orderTank;
        int wanted = Math.min(Math.min(Math.abs(delta), PULSE_AMOUNT), tank.getFluidAmount());
        if (wanted <= 0) return;

        int accepted = handler.fill(EntropyFluids.stack(type, wanted), IFluidHandler.FluidAction.SIMULATE);
        if (accepted <= 0) return;
        int filled = handler.fill(EntropyFluids.stack(type, accepted), IFluidHandler.FluidAction.EXECUTE);
        if (filled > 0) tank.drain(filled, IFluidHandler.FluidAction.EXECUTE);
    }

    @Override
    public List<Component> getEntropyTooltip() {
        String sign = target >= 0 ? "+" : "";
        return List.of(
                getDisplayName().copy().withStyle(ChatFormatting.WHITE),
                Component.translatable("overlay.temporalindustries.entropy_glasses.balancer.target",
                        sign + EntropyDisplay.formatBalance(target)).withStyle(ChatFormatting.GRAY),
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(orderTank.getFluidAmount()), EntropyDisplay.formatFluid(TANK_CAPACITY))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.ORDER)),
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(chaosTank.getFluidAmount()), EntropyDisplay.formatFluid(TANK_CAPACITY))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.CHAOS)));
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
    public Component getDisplayName() {
        return Component.translatable("block.temporalindustries.entropy_balancer");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new EntropyBalancerMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        tag.putInt("Target", target);
        if (outputPos != null) tag.putLong("OutputPos", outputPos.asLong());
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("OrderTank")) orderTank.readFromNBT(registries, tag.getCompound("OrderTank"));
        if (tag.contains("ChaosTank")) chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        target = Math.clamp(tag.getInt("Target"), -MAX_TARGET, MAX_TARGET);
        outputPos = tag.contains("OutputPos") ? BlockPos.of(tag.getLong("OutputPos")) : null;
    }
}
