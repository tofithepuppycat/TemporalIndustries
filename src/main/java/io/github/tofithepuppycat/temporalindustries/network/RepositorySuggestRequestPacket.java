package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.menu.TimelineViewMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Client -> server: asks which existing repositories an unbound Chronosphere or Chronovault could adopt whose
 * names match the text typed so far.
 */
public class RepositorySuggestRequestPacket implements CustomPacketPayload {
    public static final Type<RepositorySuggestRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "repository_suggest_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RepositorySuggestRequestPacket> STREAM_CODEC =
            StreamCodec.of(RepositorySuggestRequestPacket::encode, RepositorySuggestRequestPacket::decode);

    public static final int MAX_SUGGESTIONS = 5;

    private final BlockPos machinePos;
    private final String query;

    public RepositorySuggestRequestPacket(BlockPos machinePos, String query) {
        this.machinePos = machinePos;
        this.query = query;
    }

    public static void encode(RegistryFriendlyByteBuf buf, RepositorySuggestRequestPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeUtf(packet.query, TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH * 4);
    }

    public static RepositorySuggestRequestPacket decode(RegistryFriendlyByteBuf buf) {
        return new RepositorySuggestRequestPacket(buf.readBlockPos(), buf.readUtf(TemporalWorldData.MAX_REPOSITORY_NAME_LENGTH * 4));
    }

    public static void handle(RepositorySuggestRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof TimelineViewMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;
            if (!(sender.level().getBlockEntity(packet.machinePos) instanceof AbstractTimelineMachineBlockEntity machine)) return;
            if (machine.isRepositoryBound()) return;

            TemporalWorldData data = TemporalWorldData.get(sender.server);
            List<String> names = data.suggestAvailableRepositoryNames(
                    packet.query, sender.level().dimension().location(), MAX_SUGGESTIONS);
            PacketDistributor.sendToPlayer(sender, new RepositorySuggestSyncPacket(packet.query, names));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
