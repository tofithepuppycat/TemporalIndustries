package io.github.tofithepuppycat.temporalindustries.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToClient(
                ItemCostSyncPacket.TYPE,
                ItemCostSyncPacket.STREAM_CODEC,
                ItemCostSyncPacket::handle);

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
                ChronosphereMapRequestPacket.TYPE,
                ChronosphereMapRequestPacket.STREAM_CODEC,
                ChronosphereMapRequestPacket::handle);

        registrar.playToClient(
                ChronosphereMapSyncPacket.TYPE,
                ChronosphereMapSyncPacket.STREAM_CODEC,
                ChronosphereMapSyncPacket::handle);

        registrar.playToServer(
                ChronosphereBindRepositoryPacket.TYPE,
                ChronosphereBindRepositoryPacket.STREAM_CODEC,
                ChronosphereBindRepositoryPacket::handle);

        registrar.playToServer(
                RepositorySuggestRequestPacket.TYPE,
                RepositorySuggestRequestPacket.STREAM_CODEC,
                RepositorySuggestRequestPacket::handle);

        registrar.playToClient(
                RepositorySuggestSyncPacket.TYPE,
                RepositorySuggestSyncPacket.STREAM_CODEC,
                RepositorySuggestSyncPacket::handle);

        registrar.playToServer(
                ChronosphereUnbindRepositoryPacket.TYPE,
                ChronosphereUnbindRepositoryPacket.STREAM_CODEC,
                ChronosphereUnbindRepositoryPacket::handle);

        registrar.playToServer(
                ChronosphereSetSaveEntitiesPacket.TYPE,
                ChronosphereSetSaveEntitiesPacket.STREAM_CODEC,
                ChronosphereSetSaveEntitiesPacket::handle);

        registrar.playToServer(
                ChronosphereSetChargeContainersPacket.TYPE,
                ChronosphereSetChargeContainersPacket.STREAM_CODEC,
                ChronosphereSetChargeContainersPacket::handle);

        registrar.playToServer(
                TimelineMachineDeleteHistoryPacket.TYPE,
                TimelineMachineDeleteHistoryPacket.STREAM_CODEC,
                TimelineMachineDeleteHistoryPacket::handle);

        registrar.playToServer(
                TimelineMachineDeleteCommitPacket.TYPE,
                TimelineMachineDeleteCommitPacket.STREAM_CODEC,
                TimelineMachineDeleteCommitPacket::handle);

        registrar.playToServer(
                TimelineMachineCommitPacket.TYPE,
                TimelineMachineCommitPacket.STREAM_CODEC,
                TimelineMachineCommitPacket::handle);

        registrar.playToServer(
                GlueRegionRequestPacket.TYPE,
                GlueRegionRequestPacket.STREAM_CODEC,
                GlueRegionRequestPacket::handle);

        registrar.playToClient(
                GlueRegionSyncPacket.TYPE,
                GlueRegionSyncPacket.STREAM_CODEC,
                GlueRegionSyncPacket::handle);

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

        registrar.playToClient(
                TimelineJumpEffectPacket.TYPE,
                TimelineJumpEffectPacket.STREAM_CODEC,
                TimelineJumpEffectPacket::handle);

        registrar.playToClient(
                TimelineBlockChangeFlashPacket.TYPE,
                TimelineBlockChangeFlashPacket.STREAM_CODEC,
                TimelineBlockChangeFlashPacket::handle);

        registrar.playToClient(
                LifelineShieldPacket.TYPE,
                LifelineShieldPacket.STREAM_CODEC,
                LifelineShieldPacket::handle);

        registrar.playToServer(
                LootGeneratorSetTablePacket.TYPE,
                LootGeneratorSetTablePacket.STREAM_CODEC,
                LootGeneratorSetTablePacket::handle);

        registrar.playToServer(
                LootGeneratorTriggerRollPacket.TYPE,
                LootGeneratorTriggerRollPacket.STREAM_CODEC,
                LootGeneratorTriggerRollPacket::handle);

        registrar.playToServer(
                LootGeneratorSetLuckPacket.TYPE,
                LootGeneratorSetLuckPacket.STREAM_CODEC,
                LootGeneratorSetLuckPacket::handle);

        registrar.playToServer(
                LootGeneratorStopPacket.TYPE,
                LootGeneratorStopPacket.STREAM_CODEC,
                LootGeneratorStopPacket::handle);

        registrar.playToServer(
                LootGeneratorToggleRepeatPacket.TYPE,
                LootGeneratorToggleRepeatPacket.STREAM_CODEC,
                LootGeneratorToggleRepeatPacket::handle);

        registrar.playToServer(
                LootTableSuggestionsRequestPacket.TYPE,
                LootTableSuggestionsRequestPacket.STREAM_CODEC,
                LootTableSuggestionsRequestPacket::handle);

        registrar.playToClient(
                LootTableSuggestionsSyncPacket.TYPE,
                LootTableSuggestionsSyncPacket.STREAM_CODEC,
                LootTableSuggestionsSyncPacket::handle);

        registrar.playToServer(
                EntropyManipulatorCycleRecipePacket.TYPE,
                EntropyManipulatorCycleRecipePacket.STREAM_CODEC,
                EntropyManipulatorCycleRecipePacket::handle);

        registrar.playToServer(
                ChronoRemoteActionPacket.TYPE,
                ChronoRemoteActionPacket.STREAM_CODEC,
                ChronoRemoteActionPacket::handle);

        registrar.playToServer(
                ChronoLensSavePacket.TYPE,
                ChronoLensSavePacket.STREAM_CODEC,
                ChronoLensSavePacket::handle);

        registrar.playToClient(
                ChronoRiftChunkTrackedSyncPacket.TYPE,
                ChronoRiftChunkTrackedSyncPacket.STREAM_CODEC,
                ChronoRiftChunkTrackedSyncPacket::handle);
    }
}
