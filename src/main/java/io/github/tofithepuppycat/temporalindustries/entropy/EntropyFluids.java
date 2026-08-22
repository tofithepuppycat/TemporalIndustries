package io.github.tofithepuppycat.temporalindustries.entropy;

import io.github.tofithepuppycat.temporalindustries.Registration;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * Bridge between the orb/xp-like {@link EntropyType} units the world spits out and the liquid
 * Order/Chaos fluids that every tank, cell and anchor actually stores. Everything downstream of an
 * orb is measured in mB; {@link #MB_PER_UNIT} is the single place that ratio lives.
 */
@SuppressWarnings("null")
public final class EntropyFluids {
    /** One orb unit condenses into this many mB of its liquid form. */
    public static final int MB_PER_UNIT = 50;

    private EntropyFluids() {}

    public static Fluid fluid(EntropyType type) {
        return type == EntropyType.ORDER ? Registration.ORDER_FLUID.get() : Registration.CHAOS_FLUID.get();
    }

    /** The entropy type of an Order/Chaos fluid, or null for any other fluid. */
    @Nullable
    public static EntropyType typeOf(Fluid fluid) {
        if (fluid.isSame(Registration.ORDER_FLUID.get())) return EntropyType.ORDER;
        if (fluid.isSame(Registration.CHAOS_FLUID.get())) return EntropyType.CHAOS;
        return null;
    }

    public static FluidStack stack(EntropyType type, int millibuckets) {
        return new FluidStack(fluid(type), millibuckets);
    }

    public static int toMillibuckets(int units) {
        return units * MB_PER_UNIT;
    }
}
