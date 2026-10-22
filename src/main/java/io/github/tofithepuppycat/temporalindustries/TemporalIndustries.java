package io.github.tofithepuppycat.temporalindustries;

import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCellCompat;
import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCompat;
import io.github.tofithepuppycat.temporalindustries.config.TemporalIndustriesConfig;
import io.github.tofithepuppycat.temporalindustries.device.ChronoActionRecorder;
import io.github.tofithepuppycat.temporalindustries.device.EchoKillListener;
import io.github.tofithepuppycat.temporalindustries.device.TimeDilationListener;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftVoidWalkListener;
import io.github.tofithepuppycat.temporalindustries.dimension.ChronoRiftChunkListener;
import io.github.tofithepuppycat.temporalindustries.energy.EnergyCostReloadListener;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import io.github.tofithepuppycat.temporalindustries.network.ItemCostSyncPacket;
import io.github.tofithepuppycat.temporalindustries.network.NetworkHandler;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@Mod(TemporalIndustries.MODID)
public class TemporalIndustries
{
    public static final String MODID = "temporalindustries";

    public TemporalIndustries(IEventBus modEventBus, ModContainer modContainer)
    {
        Registration.init(modEventBus);

        modEventBus.addListener(Registration::registerCapabilities);
        modEventBus.addListener(NetworkHandler::register);

        if (ModList.get().isLoaded("curios")) {
            modEventBus.addListener(CuriosCellCompat::registerCapabilities);
            modEventBus.addListener(CuriosCompat::registerCapabilities);
        }

        modContainer.registerConfig(ModConfig.Type.COMMON, TemporalIndustriesConfig.SPEC);

        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new EnergyCostReloadListener()));
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> ItemEnergyCosts.compute(event.getServer()));

        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) ->
                event.getRelevantPlayers().forEach(player ->
                        PacketDistributor.sendToPlayer(player, ItemCostSyncPacket.fromResolved())));

        // LOWEST so interactions another mod already cancelled (claims, protection) never get recorded.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChronoActionRecorder::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChronoActionRecorder::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChronoActionRecorder::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChronoActionRecorder::onUseItemStop);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChronoActionRecorder::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChronoActionRecorder::onEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChronoActionRecorder::onAttackEntity);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChronoActionRecorder::onEntityMount);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, EchoKillListener::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onContainerClose);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onServerTick);

        NeoForge.EVENT_BUS.addListener(ChronoRiftChunkListener::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(ChronoRiftChunkListener::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(ChronoRiftChunkListener::onServerTick);

        NeoForge.EVENT_BUS.addListener(ChronoRiftVoidWalkListener::onServerTick);
        NeoForge.EVENT_BUS.addListener(ChronoRiftVoidWalkListener::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ChronoRiftVoidWalkListener::onLivingFall);

        NeoForge.EVENT_BUS.addListener(TimeDilationListener::onServerTick);
    }
}
