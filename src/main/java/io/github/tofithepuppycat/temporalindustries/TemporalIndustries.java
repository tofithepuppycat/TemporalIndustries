package io.github.tofithepuppycat.temporalindustries;

import io.github.tofithepuppycat.temporalindustries.config.TemporalIndustriesConfig;
import io.github.tofithepuppycat.temporalindustries.device.ChronoActionRecorder;
import io.github.tofithepuppycat.temporalindustries.energy.EnergyCostReloadListener;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import io.github.tofithepuppycat.temporalindustries.network.NetworkHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
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

        modContainer.registerConfig(ModConfig.Type.COMMON, TemporalIndustriesConfig.SPEC);

        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new EnergyCostReloadListener()));
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> ItemEnergyCosts.compute(event.getServer()));

        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onEntityPlace);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onContainerClose);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ChronoActionRecorder::onServerTick);
    }
}
