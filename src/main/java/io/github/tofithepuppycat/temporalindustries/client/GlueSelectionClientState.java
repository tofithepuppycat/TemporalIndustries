package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/** Client-side cache of the last-synced glued regions, used by {@link GlueSelectionRenderer}. */
public final class GlueSelectionClientState {
    private static ResourceLocation dimension;
    private static List<BoundingBox> regions = List.of();

    private GlueSelectionClientState() {}

    public static void updateFromServer(ResourceLocation dim, List<BoundingBox> newRegions) {
        dimension = dim;
        regions = newRegions;
    }

    public static void clear() {
        dimension = null;
        regions = List.of();
    }

    /** Returns the last-synced regions, or empty if they belong to a different dimension. */
    public static List<BoundingBox> getRegions(ResourceLocation currentDimension) {
        return dimension != null && dimension.equals(currentDimension) ? regions : List.of();
    }
}
