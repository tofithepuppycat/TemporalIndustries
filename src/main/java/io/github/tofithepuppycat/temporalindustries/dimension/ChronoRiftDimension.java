package io.github.tofithepuppycat.temporalindustries.dimension;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** The Chrono Rift: a shared, data-driven void dimension (see
 * {@code data/temporalindustries/dimension/chrono_rift.json}) that {@link
 * io.github.tofithepuppycat.temporalindustries.timeline.ChronoRiftFiller} paints with
 * reconstructed Overworld history on demand, chunk by chunk. */
public final class ChronoRiftDimension {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_rift");
    public static final ResourceKey<Level> LEVEL_KEY = ResourceKey.create(Registries.DIMENSION, ID);

    private ChronoRiftDimension() {}

    @Nullable
    public static ServerLevel getLevel(MinecraftServer server) {
        return server.getLevel(LEVEL_KEY);
    }
}
