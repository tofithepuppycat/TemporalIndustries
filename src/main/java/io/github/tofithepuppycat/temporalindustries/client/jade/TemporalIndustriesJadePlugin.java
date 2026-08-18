package io.github.tofithepuppycat.temporalindustries.client.jade;

import io.github.tofithepuppycat.temporalindustries.block.EchoProjector;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Discovered by Jade via the {@link WailaPlugin} annotation (NeoForge scans annotated classes at
 * mod-load time; nothing here runs, or even loads, when Jade isn't installed). No modid is passed
 * to the annotation since this plugin ships inside Temporal Industries itself rather than as a
 * separate Jade addon. See {@link ChronoProjectorComponentProvider} for what's actually shown.
 */
@WailaPlugin
public class TemporalIndustriesJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ChronoProjectorComponentProvider.INSTANCE, EchoProjector.class);
    }
}
