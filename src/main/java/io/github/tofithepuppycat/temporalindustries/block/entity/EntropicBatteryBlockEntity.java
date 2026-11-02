package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.EntropicBatteryMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lower tier of {@link io.github.tofithepuppycat.temporalindustries.block.EntropyCondenser}: drains
 * liquid Order/Chaos out of a cell in its input slot into its tanks and fills a cell in its
 * output slot from them, no power involved. Batteries that touch face to face form one multiblock
 * battery whose tanks act as a single pool: every member's menu, fluid handler and cell slots work
 * on the combined contents and capacity.
 */
@SuppressWarnings("null")
public class EntropicBatteryBlockEntity extends BlockEntity implements Container, MenuProvider, EntropyInfoProvider {
    public static final int TANK_CAPACITY = 16_000;
    /** Most batteries a single multiblock battery will include. */
    public static final int MAX_GROUP_SIZE = 256;
    private static final AtomicInteger TOPOLOGY_VERSION = new AtomicInteger();
    private static final int DRAIN_PER_TICK = 25; // mB pulled out of the slotted cell per tick
    private static final int OUTPUT_FILL_PER_TICK = 25; // mB pushed from each tank into the output cell per tick
    private static final int SLOT_COUNT = 2;
    public static final int CELL_SLOT = 0;
    public static final int OUTPUT_SLOT = 1;

    private final class CondenserFluidHandler implements IFluidHandler {
        @Override public int getTanks() { return 2; }

        @Override public @NotNull FluidStack getFluidInTank(int tank) {
            return stackOf(tank == 0 ? EntropyType.ORDER : EntropyType.CHAOS, groupAmount(tank == 0 ? EntropyType.ORDER : EntropyType.CHAOS));
        }

        @Override public int getTankCapacity(int tank) {
            return groupCapacity();
        }

        @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return tank == 0 ? orderTank.isFluidValid(stack) : chaosTank.isFluidValid(stack);
        }

        @Override public int fill(FluidStack resource, FluidAction action) {
            if (orderTank.isFluidValid(resource)) return groupFill(EntropyType.ORDER, resource.getAmount(), action);
            if (chaosTank.isFluidValid(resource)) return groupFill(EntropyType.CHAOS, resource.getAmount(), action);
            return 0;
        }

        @Override public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (orderTank.isFluidValid(resource)) return stackOf(EntropyType.ORDER, groupDrain(EntropyType.ORDER, resource.getAmount(), action));
            if (chaosTank.isFluidValid(resource)) return stackOf(EntropyType.CHAOS, groupDrain(EntropyType.CHAOS, resource.getAmount(), action));
            return FluidStack.EMPTY;
        }

        @Override public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            if (groupAmount(EntropyType.ORDER) > 0) return stackOf(EntropyType.ORDER, groupDrain(EntropyType.ORDER, maxDrain, action));
            if (groupAmount(EntropyType.CHAOS) > 0) return stackOf(EntropyType.CHAOS, groupDrain(EntropyType.CHAOS, maxDrain, action));
            return FluidStack.EMPTY;
        }

        private FluidStack stackOf(EntropyType type, int amount) {
            return amount <= 0 ? FluidStack.EMPTY : EntropyFluids.stack(type, amount);
        }
    }

    private final FluidTank orderTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.ORDER_FLUID.get());
        }
    };
    private final FluidTank chaosTank = new FluidTank(TANK_CAPACITY) {
        @Override public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isSame(Registration.CHAOS_FLUID.get());
        }
    };
    private final CondenserFluidHandler fluidHandler = new CondenserFluidHandler();

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final IItemHandler inventory = new InvWrapper(this);

    public EntropicBatteryBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.ENTROPIC_BATTERY_BLOCK_ENTITY.get(), pos, state);
    }

    public IItemHandler getItemHandler() {
        return inventory;
    }

    public IFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public FluidTank getOrderTank() {
        return orderTank;
    }

    public FluidTank getChaosTank() {
        return chaosTank;
    }

    private FluidTank tankOf(EntropyType type) {
        return type == EntropyType.ORDER ? orderTank : chaosTank;
    }

    private List<EntropicBatteryBlockEntity> group = List.of();
    private int groupVersion = -1;

    @Override
    public void onLoad() {
        super.onLoad();
        TOPOLOGY_VERSION.incrementAndGet();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        TOPOLOGY_VERSION.incrementAndGet();
    }

    /** Every battery connected to this one face to face, this one included, in a stable order. */
    public List<EntropicBatteryBlockEntity> getGroup() {
        int version = TOPOLOGY_VERSION.get();
        if (groupVersion == version && !group.isEmpty()) return group;

        List<EntropicBatteryBlockEntity> found = new ArrayList<>();
        if (level != null) {
            ArrayDeque<EntropicBatteryBlockEntity> queue = new ArrayDeque<>();
            Set<BlockPos> seen = new HashSet<>();
            seen.add(worldPosition);
            queue.add(this);
            while (!queue.isEmpty() && found.size() < MAX_GROUP_SIZE) {
                EntropicBatteryBlockEntity current = queue.poll();
                found.add(current);
                for (Direction direction : Direction.values()) {
                    BlockPos neighbour = current.worldPosition.relative(direction);
                    if (!level.isLoaded(neighbour) || !seen.add(neighbour)) continue;
                    if (level.getBlockEntity(neighbour) instanceof EntropicBatteryBlockEntity other && !other.isRemoved()) queue.add(other);
                }
            }
        } else {
            found.add(this);
        }
        found.sort(Comparator.comparingLong(be -> be.worldPosition.asLong()));
        List<EntropicBatteryBlockEntity> result = List.copyOf(found);
        for (EntropicBatteryBlockEntity member : result) {
            member.group = result;
            member.groupVersion = version;
        }
        return result;
    }

    public int getGroupSize() {
        return getGroup().size();
    }

    public int groupAmount(EntropyType type) {
        long total = 0;
        for (EntropicBatteryBlockEntity member : getGroup()) total += member.tankOf(type).getFluidAmount();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public int groupCapacity() {
        return getGroup().size() * TANK_CAPACITY;
    }

    /** Fills the pool, spreading the fluid over the members' tanks, and returns how much went in. */
    public int groupFill(EntropyType type, int amount, IFluidHandler.FluidAction action) {
        int remaining = amount;
        for (EntropicBatteryBlockEntity member : getGroup()) {
            if (remaining <= 0) break;
            int filled = member.tankOf(type).fill(EntropyFluids.stack(type, remaining), action);
            if (filled > 0 && action.execute()) member.markTanksChanged();
            remaining -= filled;
        }
        return amount - remaining;
    }

    /** Drains the pool and returns how much came out. */
    public int groupDrain(EntropyType type, int amount, IFluidHandler.FluidAction action) {
        int remaining = amount;
        for (EntropicBatteryBlockEntity member : getGroup()) {
            if (remaining <= 0) break;
            FluidStack drained = member.tankOf(type).drain(remaining, action);
            if (!drained.isEmpty() && action.execute()) member.markTanksChanged();
            remaining -= drained.getAmount();
        }
        return amount - remaining;
    }

    private void markTanksChanged() {
        setChanged();
        syncToClients();
    }

    @Override
    public List<Component> getEntropyTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(getDisplayName().copy().withStyle(ChatFormatting.WHITE));
        if (getGroupSize() > 1) {
            lines.add(Component.translatable("block.temporalindustries.entropic_battery.combined", getGroupSize()).withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.addAll(List.of(
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(groupAmount(EntropyType.ORDER)), EntropyDisplay.formatFluid(groupCapacity()))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.ORDER)),
                Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                        EntropyDisplay.formatFluid(groupAmount(EntropyType.CHAOS)), EntropyDisplay.formatFluid(groupCapacity()))
                        .withStyle(ChatFormatting.GRAY).append(EntropyDisplay.unit(EntropyType.CHAOS))));
        return lines;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, EntropicBatteryBlockEntity be) {
        if (level.isClientSide) return;
        be.drainCell();
        be.fillOutput();
    }

    private void drainCell() {
        ItemStack stack = items.get(CELL_SLOT);
        if (!(stack.getItem() instanceof EntropyReceptacle receptacle)) return;

        int drained = 0;
        for (EntropyType type : EntropyType.values()) {
            drained += drainInto(receptacle, stack, type);
        }
        if (drained <= 0) return;

        setChanged();
        syncToClients();
    }

    /**
     * Moves up to {@link #DRAIN_PER_TICK} mB of {@code type} out of the cell and into its tank,
     * capped by both the cell's contents and the tank's remaining space.
     */
    private int drainInto(EntropyReceptacle receptacle, ItemStack stack, EntropyType type) {
        int space = groupCapacity() - groupAmount(type);
        int amount = Math.min(DRAIN_PER_TICK, Math.min(receptacle.amount(stack, type), space));
        if (amount <= 0) return 0;

        int drained = receptacle.drain(stack, type, amount);
        if (drained <= 0) return 0;

        groupFill(type, drained, IFluidHandler.FluidAction.EXECUTE);
        return drained;
    }

    /**
     * Pushes Order then Chaos out of the tanks into whatever {@link EntropyReceptacle} sits in
     * {@link #OUTPUT_SLOT}, up to {@link #OUTPUT_FILL_PER_TICK} of each per tick.
     */
    private void fillOutput() {
        ItemStack stack = items.get(OUTPUT_SLOT);
        if (!(stack.getItem() instanceof EntropyReceptacle receptacle)) return;

        int filledOrder = fillFrom(EntropyType.ORDER, stack, receptacle);
        int filledChaos = fillFrom(EntropyType.CHAOS, stack, receptacle);
        if (filledOrder <= 0 && filledChaos <= 0) return;

        setChanged();
        syncToClients();
    }

    /** Drains up to {@link #OUTPUT_FILL_PER_TICK} mB of {@code type} from the pool into the receptacle. */
    private int fillFrom(EntropyType type, ItemStack stack, EntropyReceptacle receptacle) {
        int available = Math.min(OUTPUT_FILL_PER_TICK, groupAmount(type));
        if (available <= 0) return 0;

        int accepted = receptacle.fill(stack, type, available);
        if (accepted <= 0) return 0;

        groupDrain(type, accepted, IFluidHandler.FluidAction.EXECUTE);
        return accepted;
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

    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.get(CELL_SLOT).isEmpty() && items.get(OUTPUT_SLOT).isEmpty(); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        setChanged();
    }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void clearContent() { items.clear(); }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.temporalindustries.entropic_battery");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new EntropicBatteryMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("OrderTank", orderTank.writeToNBT(registries, new CompoundTag()));
        tag.put("ChaosTank", chaosTank.writeToNBT(registries, new CompoundTag()));
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("OrderTank")) orderTank.readFromNBT(registries, tag.getCompound("OrderTank"));
        if (tag.contains("ChaosTank")) chaosTank.readFromNBT(registries, tag.getCompound("ChaosTank"));
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
