package io.github.tofithepuppycat.temporalindustries.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(
                TimelinePreviewRequestPacket.TYPE,
                TimelinePreviewRequestPacket.STREAM_CODEC,
                TimelinePreviewRequestPacket::handle);

        registrar.playToClient(
                TimelinePreviewSyncPacket.TYPE,
                TimelinePreviewSyncPacket.STREAM_CODEC,
                TimelinePreviewSyncPacket::handle);

        registrar.playToServer(
                RollbackChunkPacket.TYPE,
                RollbackChunkPacket.STREAM_CODEC,
                RollbackChunkPacket::handle);

        registrar.playToClient(
                AnchorStatusPacket.TYPE,
                AnchorStatusPacket.STREAM_CODEC,
                AnchorStatusPacket::handle);

        registrar.playToServer(
                ChronosphereToggleChunkPacket.TYPE,
                ChronosphereToggleChunkPacket.STREAM_CODEC,
                ChronosphereToggleChunkPacket::handle);

        registrar.playToServer(
                UpdateChronosphereTimePacket.TYPE,
                UpdateChronosphereTimePacket.STREAM_CODEC,
                UpdateChronosphereTimePacket::handle);

        registrar.playToServer(
                ChronosphereJumpPacket.TYPE,
                ChronosphereJumpPacket.STREAM_CODEC,
                ChronosphereJumpPacket::handle);

        registrar.playToServer(
                ChronosphereStateRequestPacket.TYPE,
                ChronosphereStateRequestPacket.STREAM_CODEC,
                ChronosphereStateRequestPacket::handle);

        registrar.playToClient(
                ChronosphereStateSyncPacket.TYPE,
                ChronosphereStateSyncPacket.STREAM_CODEC,
                ChronosphereStateSyncPacket::handle);
    }
}
