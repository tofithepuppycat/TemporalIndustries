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
                TimelineMachineToggleAutoTrackPacket.TYPE,
                TimelineMachineToggleAutoTrackPacket.STREAM_CODEC,
                TimelineMachineToggleAutoTrackPacket::handle);

        registrar.playToServer(
                ChronosphereMapRequestPacket.TYPE,
                ChronosphereMapRequestPacket.STREAM_CODEC,
                ChronosphereMapRequestPacket::handle);

        registrar.playToClient(
                ChronosphereMapSyncPacket.TYPE,
                ChronosphereMapSyncPacket.STREAM_CODEC,
                ChronosphereMapSyncPacket::handle);

        registrar.playToServer(
                TimelineMachineDeleteHistoryPacket.TYPE,
                TimelineMachineDeleteHistoryPacket.STREAM_CODEC,
                TimelineMachineDeleteHistoryPacket::handle);

        registrar.playToServer(
                GlueRegionRequestPacket.TYPE,
                GlueRegionRequestPacket.STREAM_CODEC,
                GlueRegionRequestPacket::handle);

        registrar.playToClient(
                GlueRegionSyncPacket.TYPE,
                GlueRegionSyncPacket.STREAM_CODEC,
                GlueRegionSyncPacket::handle);

        registrar.playToServer(
                ChronoMarkerMapRequestPacket.TYPE,
                ChronoMarkerMapRequestPacket.STREAM_CODEC,
                ChronoMarkerMapRequestPacket::handle);

        registrar.playToClient(
                ChronoMarkerMapSyncPacket.TYPE,
                ChronoMarkerMapSyncPacket.STREAM_CODEC,
                ChronoMarkerMapSyncPacket::handle);

        registrar.playToServer(
                ChronoMarkerSaveSelectionPacket.TYPE,
                ChronoMarkerSaveSelectionPacket.STREAM_CODEC,
                ChronoMarkerSaveSelectionPacket::handle);

        registrar.playToServer(
                EntropyCondenserSetRangePacket.TYPE,
                EntropyCondenserSetRangePacket.STREAM_CODEC,
                EntropyCondenserSetRangePacket::handle);

        registrar.playToServer(
                AnchorModeCyclePacket.TYPE,
                AnchorModeCyclePacket.STREAM_CODEC,
                AnchorModeCyclePacket::handle);

        registrar.playToServer(
                CellTransferAmountPacket.TYPE,
                CellTransferAmountPacket.STREAM_CODEC,
                CellTransferAmountPacket::handle);

        registrar.playToClient(
                AnchorRewindEffectPacket.TYPE,
                AnchorRewindEffectPacket.STREAM_CODEC,
                AnchorRewindEffectPacket::handle);

        registrar.playToServer(
                LootGeneratorSetTablePacket.TYPE,
                LootGeneratorSetTablePacket.STREAM_CODEC,
                LootGeneratorSetTablePacket::handle);

        registrar.playToServer(
                LootGeneratorTriggerRollPacket.TYPE,
                LootGeneratorTriggerRollPacket.STREAM_CODEC,
                LootGeneratorTriggerRollPacket::handle);

        registrar.playToServer(
                LootTableSuggestionsRequestPacket.TYPE,
                LootTableSuggestionsRequestPacket.STREAM_CODEC,
                LootTableSuggestionsRequestPacket::handle);

        registrar.playToClient(
                LootTableSuggestionsSyncPacket.TYPE,
                LootTableSuggestionsSyncPacket.STREAM_CODEC,
                LootTableSuggestionsSyncPacket::handle);
    }
}
