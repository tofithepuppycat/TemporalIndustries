package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Client-side toggle for {@link EntropyCondenserRangeRenderer}: at most one Entropy Condenser can
 * have its absorb-range perimeter shown at a time. Purely visual, never synced to the server. */
public final class EntropyCondenserRangeClientState {
    @Nullable private static ResourceLocation dimension;
    @Nullable private static BlockPos pos;

    private EntropyCondenserRangeClientState() {}

    public static void show(ResourceLocation dim, BlockPos machinePos) {
        dimension = dim;
        pos = machinePos;
    }

    public static void clear() {
        dimension = null;
        pos = null;
    }

    public static boolean isShowing(ResourceLocation dim, BlockPos machinePos) {
        return machinePos.equals(pos) && dim.equals(dimension);
    }

    @Nullable
    public static BlockPos getPos(ResourceLocation currentDimension) {
        return currentDimension.equals(dimension) ? pos : null;
    }
}
