package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client -> server: request the Overworld's dimension-wide trunk timeline for {@link
 * io.github.tofithepuppycat.temporalindustries.client.screen.ChronoLensScreen}. Sent once on open
 * and periodically thereafter, same pattern as {@link ChronoMarkerMapRequestPacket}. No arguments —
 * there's no block/menu to scope the request to, and the trunk is dimension-wide by nature. */
public class ChronoLensRequestPacket implements CustomPacketPayload {
    public static final Type<ChronoLensRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_lens_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoLensRequestPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {}, buf -> new ChronoLensRequestPacket());

    public static void handle(ChronoLensRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            TemporalTimeline timeline = TemporalWorldData.get(sender.getServer()).getTimeline(Level.OVERWORLD.location());
            List<TemporalCommit> trunk = timeline == null ? List.of() : timeline.getTrunkCommits();

            Map<Long, Long> localParents = new HashMap<>();
            for (TemporalCommit commit : trunk) localParents.put(commit.getId(), commit.getParentId());

            long headCommitId = timeline == null ? -1L : timeline.getLatestCommitId();
            long placedGameTime = trunk.isEmpty() ? 0L : trunk.get(0).getGameTime();

            PacketDistributor.sendToPlayer(sender, new TimelinePreviewSyncPacket(
                    BlockPos.ZERO,
                    placedGameTime,
                    placedGameTime,
                    sender.level().getGameTime(),
                    trunk,
                    localParents,
                    headCommitId,
                    -1L,
                    Map.of(),
                    List.of(),
                    List.of(),
                    headCommitId));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
