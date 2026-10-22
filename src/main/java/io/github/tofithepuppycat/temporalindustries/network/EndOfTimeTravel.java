package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftDimension;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftVoidWalkListener;
import io.github.tofithepuppycat.temporalindustries.dimension.EndOfTimeDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Moves a player to the End of Time. */
public final class EndOfTimeTravel {
    private EndOfTimeTravel() {}

    public static void enter(ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null) return;
        if (sender.level().dimension() == ChronoRiftDimension.LEVEL_KEY
                || sender.level().dimension() == EndOfTimeDimension.LEVEL_KEY) return;

        ServerLevel level = EndOfTimeDimension.getLevel(server);
        if (level == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        PlayerTemporalState state = data.getOrCreatePlayerState(sender.getUUID());
        state.setChronoRiftOrigin(new PlayerTemporalState.ChronoRiftOrigin(
                sender.level().dimension().location(), sender.getX(), sender.getY(), sender.getZ(),
                sender.getYRot(), sender.getXRot()));
        data.setDirty();

        EndOfTimeDimension.ensurePlatform(level);
        BlockPos arrival = EndOfTimeDimension.ARRIVAL_POS;
        sender.teleportTo(level, arrival.getX() + 0.5D, arrival.getY(), arrival.getZ() + 0.5D,
                sender.getYRot(), sender.getXRot());
        ChronoRiftVoidWalkListener.protectFromFall(sender);
    }
}
