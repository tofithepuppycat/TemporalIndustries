package io.github.tofithepuppycat.temporalindustries.dimension;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/**
 * The End of Time: a plain void dimension outside the Overworld's history. No timeline machine
 * works here (see {@link RiftDeviceLock}), so nothing built in it is ever recorded or rewound.
 */
public final class EndOfTimeDimension {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "end_of_time");
    public static final ResourceKey<Level> LEVEL_KEY = ResourceKey.create(Registries.DIMENSION, ID);

    public static final BlockPos ARRIVAL_POS = new BlockPos(0, 64, 0);
    private static final int PLATFORM_RADIUS = 4;

    private EndOfTimeDimension() {}

    @Nullable
    public static ServerLevel getLevel(MinecraftServer server) {
        return server.getLevel(LEVEL_KEY);
    }

    /** Lays the arrival platform the first time it is needed; does nothing once it exists. */
    public static void ensurePlatform(ServerLevel level) {
        BlockPos floor = ARRIVAL_POS.below();
        level.getChunk(floor);
        if (!level.getBlockState(floor).isAir()) return;

        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                level.setBlockAndUpdate(floor.offset(dx, 0, dz), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            }
        }
    }
}
