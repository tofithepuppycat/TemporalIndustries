package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.screen.PortableChronoMarkerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: the block changes between a player's latest two Portable ChronoMarker marks.
 * Sent directly in response to the item's use() (see PortableChronoMarkerItem) rather than through
 * a request/sync pair like TimelinePreviewSyncPacket, since the server already owns the interaction
 * that triggered it and there's no per-machine menu to key a request off of.
 */
public class ChronoMarkerDiffSyncPacket implements CustomPacketPayload {
    public static final Type<ChronoMarkerDiffSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_marker_diff_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoMarkerDiffSyncPacket> STREAM_CODEC =
            StreamCodec.of(ChronoMarkerDiffSyncPacket::encode, ChronoMarkerDiffSyncPacket::decode);

    /** A single changed block, stripped of block-entity NBT — the diff screen only shows old/new
     * block identity per position, not full state restoration. */
    public record Entry(BlockPos pos, BlockState oldState, BlockState newState) {}

    private final long fromGameTime;
    private final long toGameTime;
    private final List<Entry> entries;

    public ChronoMarkerDiffSyncPacket(long fromGameTime, long toGameTime, List<Entry> entries) {
        this.fromGameTime = fromGameTime;
        this.toGameTime = toGameTime;
        this.entries = entries;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoMarkerDiffSyncPacket packet) {
        buf.writeLong(packet.fromGameTime);
        buf.writeLong(packet.toGameTime);
        buf.writeVarInt(packet.entries.size());
        for (Entry entry : packet.entries) {
            buf.writeBlockPos(entry.pos());
            buf.writeVarInt(Block.getId(entry.oldState()));
            buf.writeVarInt(Block.getId(entry.newState()));
        }
    }

    public static ChronoMarkerDiffSyncPacket decode(RegistryFriendlyByteBuf buf) {
        long fromGameTime = buf.readLong();
        long toGameTime = buf.readLong();
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            BlockState oldState = Block.stateById(buf.readVarInt());
            BlockState newState = Block.stateById(buf.readVarInt());
            entries.add(new Entry(pos, oldState, newState));
        }
        return new ChronoMarkerDiffSyncPacket(fromGameTime, toGameTime, entries);
    }

    public static void handle(ChronoMarkerDiffSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(
                new PortableChronoMarkerScreen(packet.fromGameTime, packet.toGameTime, packet.entries)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
