package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposes an {@link EntropyReceptacle} stack (cells, Temporal Anchor) as a fluid container, one tank
 * per accepted entropy type, so pipes/tanks/other mods' fluid machinery can fill and drain it like
 * any liquid item. Purely a view over the stack's data components.
 */
@SuppressWarnings("null")
public class EntropyItemFluidHandler implements IFluidHandlerItem {
    private final ItemStack stack;
    private final EntropyReceptacle receptacle;
    private final List<EntropyType> types = new ArrayList<>(2);

    public EntropyItemFluidHandler(ItemStack stack) {
        this.stack = stack;
        this.receptacle = (EntropyReceptacle) stack.getItem();
        for (EntropyType type : EntropyType.values()) {
            if (receptacle.accepts(type)) types.add(type);
        }
    }

    @Override
    public @NotNull ItemStack getContainer() {
        return stack;
    }

    @Override
    public int getTanks() {
        return types.size();
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        EntropyType type = types.get(tank);
        int amount = receptacle.amount(stack, type);
        return amount <= 0 ? FluidStack.EMPTY : EntropyFluids.stack(type, amount);
    }

    @Override
    public int getTankCapacity(int tank) {
        return receptacle.capacity(types.get(tank));
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack resource) {
        return EntropyFluids.typeOf(resource.getFluid()) == types.get(tank);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        EntropyType type = acceptedType(resource);
        if (type == null) return 0;

        int room = receptacle.capacity(type) - receptacle.amount(stack, type);
        int fillable = Math.min(room, resource.getAmount());
        if (fillable <= 0) return 0;

        return action.execute() ? receptacle.fill(stack, type, fillable) : fillable;
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        EntropyType type = acceptedType(resource);
        if (type == null) return FluidStack.EMPTY;
        return drain(type, resource.getAmount(), action);
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        for (EntropyType type : types) {
            if (receptacle.amount(stack, type) > 0) return drain(type, maxDrain, action);
        }
        return FluidStack.EMPTY;
    }

    private FluidStack drain(EntropyType type, int maxDrain, FluidAction action) {
        int drainable = Math.min(receptacle.amount(stack, type), maxDrain);
        if (drainable <= 0) return FluidStack.EMPTY;

        int drained = action.execute() ? receptacle.drain(stack, type, drainable) : drainable;
        return drained <= 0 ? FluidStack.EMPTY : EntropyFluids.stack(type, drained);
    }

    @Nullable
    private EntropyType acceptedType(FluidStack resource) {
        if (resource.isEmpty()) return null;
        EntropyType type = EntropyFluids.typeOf(resource.getFluid());
        return type != null && receptacle.accepts(type) ? type : null;
    }
}
