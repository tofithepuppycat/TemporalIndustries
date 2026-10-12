package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client -> server: apply a batch of chunk claims/releases in a Chronosphere's 11x11 map, sent once
 * the player closes the map overlay (or the whole GUI) rather than per cell, so a rectangle or
 * freehand drag across many chunks produces one request instead of one per chunk.
 */
public class ChronosphereToggleChunkPacket implements CustomPacketPayload {
    public static final Type<ChronosphereToggleChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_toggle_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereToggleChunkPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereToggleChunkPacket::encode, ChronosphereToggleChunkPacket::decode);

    private final BlockPos machinePos;
    private final List<Long> adds;
    private final List<Long> removes;

    public ChronosphereToggleChunkPacket(BlockPos machinePos, List<Long> adds, List<Long> removes) {
        this.machinePos = machinePos;
        this.adds = adds;
        this.removes = removes;
    }

    /** Builds a batch packet from a drag's final per-chunk add/remove decisions. */
    public static ChronosphereToggleChunkPacket fromPending(BlockPos machinePos, Map<Long, Boolean> pending) {
        List<Long> adds = new ArrayList<>();
        List<Long> removes = new ArrayList<>();
        for (Map.Entry<Long, Boolean> entry : pending.entrySet()) {
            (entry.getValue() ? adds : removes).add(entry.getKey());
        }
        return new ChronosphereToggleChunkPacket(machinePos, adds, removes);
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereToggleChunkPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeVarInt(packet.adds.size());
        for (long key : packet.adds) buf.writeLong(key);
        buf.writeVarInt(packet.removes.size());
        for (long key : packet.removes) buf.writeLong(key);
    }

    public static ChronosphereToggleChunkPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos machinePos = buf.readBlockPos();
        int addCount = buf.readVarInt();
        List<Long> adds = new ArrayList<>(addCount);
        for (int i = 0; i < addCount; i++) adds.add(buf.readLong());
        int removeCount = buf.readVarInt();
        List<Long> removes = new ArrayList<>(removeCount);
        for (int i = 0; i < removeCount; i++) removes.add(buf.readLong());
        return new ChronosphereToggleChunkPacket(machinePos, adds, removes);
    }

    public static void handle(ChronosphereToggleChunkPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }

        context.enqueueWork(() -> {
            if (!(sender.containerMenu instanceof ChronosphereMenu menu)) return;
            if (!menu.getBlockPos().equals(packet.machinePos)) return;

            BlockEntity be = sender.level().getBlockEntity(packet.machinePos);
            if (!(be instanceof ChronosphereBlockEntity machine)) return;

            for (long key : packet.removes) {
                machine.toggleChunk(new ChunkPos(key), false);
            }
            for (long key : packet.adds) {
                ChronosphereBlockEntity.ToggleResult result = machine.toggleChunk(new ChunkPos(key), true);
                switch (result) {
                    case OUT_OF_BOUNDS -> sender.displayClientMessage(
                            Component.translatable("block.temporalindustries.chronosphere.out_of_bounds"), true);
                    case ALREADY_CLAIMED_ELSEWHERE -> sender.displayClientMessage(
                            Component.translatable("block.temporalindustries.chronosphere.already_tracked"), true);
                    case LIMIT_REACHED -> sender.displayClientMessage(
                            Component.translatable("block.temporalindustries.chronosphere.limit_reached"), true);
                    default -> { /* ADDED / REMOVED / IS_HOME / NOT_SELECTED need no feedback */ }
                }
            }

            ChronosphereStateRequestPacket.sendStateSync(sender, machine, packet.machinePos);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
