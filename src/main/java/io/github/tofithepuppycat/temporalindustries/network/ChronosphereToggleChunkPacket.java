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

/** Client -> server: claim or release one chunk in a Chronosphere's 5x5 map. Triggers an immediate
 * ChronosphereStateSyncPacket reply rather than waiting for the periodic poll, since the player is
 * actively looking at the map when they click a cell. */
public class ChronosphereToggleChunkPacket implements CustomPacketPayload {
    public static final Type<ChronosphereToggleChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_toggle_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereToggleChunkPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereToggleChunkPacket::encode, ChronosphereToggleChunkPacket::decode);

    private final BlockPos machinePos;
    private final long chunkKey;
    private final boolean add;

    public ChronosphereToggleChunkPacket(BlockPos machinePos, long chunkKey, boolean add) {
        this.machinePos = machinePos;
        this.chunkKey = chunkKey;
        this.add = add;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereToggleChunkPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeLong(packet.chunkKey);
        buf.writeBoolean(packet.add);
    }

    public static ChronosphereToggleChunkPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronosphereToggleChunkPacket(buf.readBlockPos(), buf.readLong(), buf.readBoolean());
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

            ChronosphereBlockEntity.ToggleResult result = machine.toggleChunk(new ChunkPos(packet.chunkKey), packet.add);
            switch (result) {
                case OUT_OF_BOUNDS -> sender.displayClientMessage(
                        Component.translatable("block.temporalindustries.chronosphere.out_of_bounds"), true);
                case ALREADY_TRACKED_ELSEWHERE -> sender.displayClientMessage(
                        Component.translatable("block.temporalindustries.chronosphere.already_tracked"), true);
                case LIMIT_REACHED -> sender.displayClientMessage(
                        Component.translatable("block.temporalindustries.chronosphere.limit_reached"), true);
                default -> { /* ADDED / REMOVED / IS_HOME / NOT_SELECTED need no feedback */ }
            }

            ChronosphereStateRequestPacket.sendStateSync(sender, machine, packet.machinePos);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
