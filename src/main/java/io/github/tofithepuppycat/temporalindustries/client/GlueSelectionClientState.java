package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Client-side cache of the last-synced glue regions, used by {@link GlueSelectionRenderer}: the
 * anti-glued regions of a dimension and the tracked regions of a linked Chronosphere.
 */
public final class GlueSelectionClientState {
    private static ResourceLocation dimension;
    private static List<BoundingBox> regions = List.of();
    private static ResourceLocation trackedDimension;
    private static List<BoundingBox> trackedRegions = List.of();

    private GlueSelectionClientState() {}

    public static void updateFromServer(ResourceLocation dim, List<BoundingBox> newRegions, boolean tracked) {
        if (tracked) {
            trackedDimension = dim;
            trackedRegions = newRegions;
        } else {
            dimension = dim;
            regions = newRegions;
        }
    }

    public static void clear() {
        dimension = null;
        regions = List.of();
        trackedDimension = null;
        trackedRegions = List.of();
    }

    /** Returns the last-synced anti-glued regions, or empty if they belong to a different dimension. */
    public static List<BoundingBox> getRegions(ResourceLocation currentDimension) {
        return dimension != null && dimension.equals(currentDimension) ? regions : List.of();
    }

    /** Returns the last-synced tracked regions, or empty if they belong to a different dimension. */
    public static List<BoundingBox> getTrackedRegions(ResourceLocation currentDimension) {
        return trackedDimension != null && trackedDimension.equals(currentDimension) ? trackedRegions : List.of();
    }
}
