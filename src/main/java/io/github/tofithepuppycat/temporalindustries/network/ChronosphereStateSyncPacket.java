package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server -> client: a Chronosphere's current claimed/blocked chunk sets, for {@link
 * ChronosphereClientState}.
 */
public class ChronosphereStateSyncPacket implements CustomPacketPayload {
    public static final Type<ChronosphereStateSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chronosphere_state_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronosphereStateSyncPacket> STREAM_CODEC =
            StreamCodec.of(ChronosphereStateSyncPacket::encode, ChronosphereStateSyncPacket::decode);

    private final BlockPos machinePos;
    private final List<Long> selectedChunkKeys;
    private final List<Long> blockedChunkKeys;
    /** How many of selectedChunkKeys this machine's repository actually holds the claim on. */
    private final int capturedCount;
    /** The named branch currently checked out for this machine's repository. */
    private final String currentBranch;
    /** Whether temporal glue has marked regions for this machine to record. */
    private final boolean hasGluedRegions;

    public ChronosphereStateSyncPacket(BlockPos machinePos, List<Long> selectedChunkKeys,
                                       List<Long> blockedChunkKeys, int capturedCount,
                                       String currentBranch, boolean hasGluedRegions) {
        this.machinePos = machinePos;
        this.selectedChunkKeys = selectedChunkKeys;
        this.blockedChunkKeys = blockedChunkKeys;
        this.capturedCount = capturedCount;
        this.currentBranch = currentBranch;
        this.hasGluedRegions = hasGluedRegions;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronosphereStateSyncPacket packet) {
        buf.writeBlockPos(packet.machinePos);
        buf.writeVarInt(packet.selectedChunkKeys.size());
        for (long key : packet.selectedChunkKeys) buf.writeLong(key);
        buf.writeVarInt(packet.blockedChunkKeys.size());
        for (long key : packet.blockedChunkKeys) buf.writeLong(key);
        buf.writeVarInt(packet.capturedCount);
        buf.writeUtf(packet.currentBranch, 64);
        buf.writeBoolean(packet.hasGluedRegions);
    }

    public static ChronosphereStateSyncPacket decode(RegistryFriendlyByteBuf buf) {
        BlockPos machinePos = buf.readBlockPos();
        int selectedCount = buf.readVarInt();
        List<Long> selected = new ArrayList<>(selectedCount);
        for (int i = 0; i < selectedCount; i++) selected.add(buf.readLong());
        int blockedCount = buf.readVarInt();
        List<Long> blocked = new ArrayList<>(blockedCount);
        for (int i = 0; i < blockedCount; i++) blocked.add(buf.readLong());
        int capturedCount = buf.readVarInt();
        String currentBranch = buf.readUtf(64);
        boolean hasGluedRegions = buf.readBoolean();
        return new ChronosphereStateSyncPacket(machinePos, selected, blocked, capturedCount, currentBranch, hasGluedRegions);
    }

    public static void handle(ChronosphereStateSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Set<Long> selected = new HashSet<>(packet.selectedChunkKeys);
            Set<Long> blocked = new HashSet<>(packet.blockedChunkKeys);
            ChronosphereClientState.updateFromServer(packet.machinePos, selected, blocked,
                    packet.capturedCount, packet.currentBranch, packet.hasGluedRegions);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
