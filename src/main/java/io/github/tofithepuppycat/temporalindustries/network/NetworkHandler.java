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
                ChronosphereStateRequestPacket.TYPE,
                ChronosphereStateRequestPacket.STREAM_CODEC,
                ChronosphereStateRequestPacket::handle);

        registrar.playToClient(
                ChronosphereStateSyncPacket.TYPE,
                ChronosphereStateSyncPacket.STREAM_CODEC,
                ChronosphereStateSyncPacket::handle);

        registrar.playToServer(
                ChronosphereToggleAutoTrackPacket.TYPE,
                ChronosphereToggleAutoTrackPacket.STREAM_CODEC,
                ChronosphereToggleAutoTrackPacket::handle);

        registrar.playToClient(
                ChronoMarkerDiffSyncPacket.TYPE,
                ChronoMarkerDiffSyncPacket.STREAM_CODEC,
                ChronoMarkerDiffSyncPacket::handle);

        registrar.playToServer(
                ChronosphereMapRequestPacket.TYPE,
                ChronosphereMapRequestPacket.STREAM_CODEC,
                ChronosphereMapRequestPacket::handle);

        registrar.playToClient(
                ChronosphereMapSyncPacket.TYPE,
                ChronosphereMapSyncPacket.STREAM_CODEC,
                ChronosphereMapSyncPacket::handle);

        registrar.playToServer(
                ChronosphereDeleteHistoryPacket.TYPE,
                ChronosphereDeleteHistoryPacket.STREAM_CODEC,
                ChronosphereDeleteHistoryPacket::handle);

        registrar.playToServer(
                GlueRegionRequestPacket.TYPE,
                GlueRegionRequestPacket.STREAM_CODEC,
                GlueRegionRequestPacket::handle);

        registrar.playToClient(
                GlueRegionSyncPacket.TYPE,
                GlueRegionSyncPacket.STREAM_CODEC,
                GlueRegionSyncPacket::handle);
    }
}
