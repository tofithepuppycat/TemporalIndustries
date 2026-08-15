package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/** Client-side cache of the currently known glued regions for whichever dimension was last synced
 * (see io.github.tofithepuppycat.temporalindustries.network.GlueRegionSyncPacket), for
 * io.github.tofithepuppycat.temporalindustries.client.GlueSelectionRenderer to draw. */
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

    /** The last-synced regions, or empty if they're for a different dimension than currentDimension
     * (stale data from before a dimension change, or nothing synced yet). */
    public static List<BoundingBox> getRegions(ResourceLocation currentDimension) {
        return dimension != null && dimension.equals(currentDimension) ? regions : List.of();
    }
}
