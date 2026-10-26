package io.github.tofithepuppycat.temporalindustries.network;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.ChronoRemoteClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: the settings of the machine a Chrono Remote Control is linked to. */
public class ChronoRemoteStateSyncPacket implements CustomPacketPayload {
    public static final Type<ChronoRemoteStateSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_remote_state_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChronoRemoteStateSyncPacket> STREAM_CODEC =
            StreamCodec.of(ChronoRemoteStateSyncPacket::encode, ChronoRemoteStateSyncPacket::decode);

    private final boolean bound;
    private final boolean chronosphere;
    private final boolean saveEntities;
    private final boolean chargeContainers;

    public ChronoRemoteStateSyncPacket(boolean bound, boolean chronosphere, boolean saveEntities, boolean chargeContainers) {
        this.bound = bound;
        this.chronosphere = chronosphere;
        this.saveEntities = saveEntities;
        this.chargeContainers = chargeContainers;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChronoRemoteStateSyncPacket packet) {
        buf.writeBoolean(packet.bound);
        buf.writeBoolean(packet.chronosphere);
        buf.writeBoolean(packet.saveEntities);
        buf.writeBoolean(packet.chargeContainers);
    }

    public static ChronoRemoteStateSyncPacket decode(RegistryFriendlyByteBuf buf) {
        return new ChronoRemoteStateSyncPacket(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(ChronoRemoteStateSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ChronoRemoteClientState.onSettingsSynced(
                packet.bound, packet.chronosphere, packet.saveEntities, packet.chargeContainers));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
