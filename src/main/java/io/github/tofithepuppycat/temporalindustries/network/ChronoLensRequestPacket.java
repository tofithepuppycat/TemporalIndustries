package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.timeline.CommitNode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client -> server: request the Overworld's dimension-wide trunk timeline for {@link
 * io.github.tofithepuppycat.temporalindustries.client.screen.ChronoLensScreen}.
 */
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

            // The Chrono Lens reads the per-dimension timeline but shares the repository-shaped
            // graph packet, so project its trunk into CommitNodes.
            List<CommitNode> nodes = new ArrayList<>(trunk.size());
            for (TemporalCommit commit : trunk) {
                nodes.add(new CommitNode(commit.getId(), commit.getParentId(), commit.getGameTime(),
                        commit.getShortHash(), commit.isPlayerMarked(), 1, commit.getTotalChangeCount(),
                        commit.getType() == TemporalCommit.Type.SNAPSHOT));
            }

            long headCommitId = timeline == null ? -1L : timeline.getLatestCommitId();
            long placedGameTime = trunk.isEmpty() ? 0L : trunk.get(0).getGameTime();

            PacketDistributor.sendToPlayer(sender, new TimelinePreviewSyncPacket(
                    BlockPos.ZERO,
                    placedGameTime,
                    placedGameTime,
                    sender.level().getGameTime(),
                    nodes,
                    Map.of(),
                    "",
                    headCommitId,
                    Map.of(),
                    Map.of(),
                    List.of(),
                    headCommitId));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
