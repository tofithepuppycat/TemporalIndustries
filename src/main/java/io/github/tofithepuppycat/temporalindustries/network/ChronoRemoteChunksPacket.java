package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.dimension.RiftDeviceLock;
import io.github.tofithepuppycat.temporalindustries.item.ChronoRemoteControlItem;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client -> server: a batch of chunk claims/releases made on the Chrono Remote Control's map, applied
 * to its linked Chronosphere. Counterpart of {@link ChronosphereToggleChunkPacket} for a player who
 * has no Chronosphere menu open.
 */
public class ChronoRemoteChunksPacket implements CustomPacketPayload {
    public static final Type<ChronoRemoteChunksPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_remote_chunks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoRemoteChunksPacket> STREAM_CODEC =
            StreamCodec.of(ChronoRemoteChunksPacket::encode, ChronoRemoteChunksPacket::decode);

    private final List<Long> adds;
    private final List<Long> removes;

    public ChronoRemoteChunksPacket(List<Long> adds, List<Long> removes) {
        this.adds = adds;
        this.removes = removes;
    }

    /** Builds a batch from the map's final per-chunk add/remove decisions. */
    public static ChronoRemoteChunksPacket fromPending(Map<Long, Boolean> pending) {
        List<Long> adds = new ArrayList<>();
        List<Long> removes = new ArrayList<>();
        for (Map.Entry<Long, Boolean> entry : pending.entrySet()) {
            (entry.getValue() ? adds : removes).add(entry.getKey());
        }
        return new ChronoRemoteChunksPacket(adds, removes);
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoRemoteChunksPacket packet) {
        buf.writeVarInt(packet.adds.size());
        for (long key : packet.adds) buf.writeLong(key);
        buf.writeVarInt(packet.removes.size());
        for (long key : packet.removes) buf.writeLong(key);
    }

    public static ChronoRemoteChunksPacket decode(RegistryFriendlyByteBuf buf) {
        int addCount = ChronosphereToggleChunkPacket.readCount(buf);
        List<Long> adds = new ArrayList<>(addCount);
        for (int i = 0; i < addCount; i++) adds.add(buf.readLong());
        int removeCount = ChronosphereToggleChunkPacket.readCount(buf);
        List<Long> removes = new ArrayList<>(removeCount);
        for (int i = 0; i < removeCount; i++) removes.add(buf.readLong());
        return new ChronoRemoteChunksPacket(adds, removes);
    }

    public static void handle(ChronoRemoteChunksPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            GlobalPos linked = ChronoRemoteControlItem.findHeldLink(sender);
            if (linked == null || RiftDeviceLock.blockIfInRift(sender.level(), sender)) return;

            ServerLevel level = sender.server.getLevel(linked.dimension());
            if (level == null || sender.level() != level || !level.isLoaded(linked.pos())
                    || !(level.getBlockEntity(linked.pos()) instanceof ChronosphereBlockEntity machine)
                    || !machine.isRepositoryBound()) {
                sender.displayClientMessage(Component.translatable("gui.temporalindustries.chrono_remote.msg.unreachable"), true);
                return;
            }

            ChronosphereToggleChunkPacket.applyBatch(sender, machine, packet.adds, packet.removes);
            PacketDistributor.sendToPlayer(sender, new ChronoRemoteStateSyncPacket(true, true,
                    machine.isSaveEntities(), machine.isChargeContainers()));
            ChronosphereStateRequestPacket.sendStateSync(sender, machine, machine.getBlockPos());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
