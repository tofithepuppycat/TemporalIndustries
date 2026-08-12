package io.github.tofithepuppycat.temporalindustries.client.jade;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronoProjectorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.device.ChronoRecording;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Shows whether the Chrono Loop Projector's loop is currently running or paused (out of energy),
 * and — while it holds a saved recording — that recording's average and peak per-tick energy cost
 * (see {@link ChronoRecording#averageEnergyPerTick()} / {@link ChronoRecording#peakEnergyPerTick()}),
 * so a player can tell at a glance whether their power supply can actually sustain it. Stored/max
 * energy itself isn't duplicated here since Jade's own energy component already shows that from
 * the block's IEnergyStorage capability.
 */
public enum ChronoProjectorComponentProvider implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!(accessor.getBlockEntity() instanceof ChronoProjectorBlockEntity be)) return;

        ChronoRecording recording = be.getCachedRecording();
        if (recording == null) {
            tooltip.add(Component.translatable("jade.temporalindustries.chrono_projector.no_recording")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        tooltip.add(Component.translatable(be.isLoopActive()
                        ? "jade.temporalindustries.chrono_projector.active"
                        : "jade.temporalindustries.chrono_projector.paused")
                .withStyle(be.isLoopActive() ? ChatFormatting.GREEN : ChatFormatting.RED));

        tooltip.add(Component.translatable("jade.temporalindustries.chrono_projector.energy_rate",
                String.format("%.1f", recording.averageEnergyPerTick()), recording.peakEnergyPerTick()));
    }

    @Override
    public ResourceLocation getUid() {
        return ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "chrono_projector");
    }
}
