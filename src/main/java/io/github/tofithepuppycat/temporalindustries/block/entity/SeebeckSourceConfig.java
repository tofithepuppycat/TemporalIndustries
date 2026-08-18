package io.github.tofithepuppycat.temporalindustries.block.entity;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Modpack-editable extra hot/cold sources for the Seebeck Generator, on top of the
 * {@code #temporalindustries:seebeck_hot_sources}/{@code seebeck_cold_sources} tags, so modpacks can
 * register new source blocks (with their own temperature) without needing a datapack. A section of
 * the shared {@link io.github.tofithepuppycat.temporalindustries.config.TemporalIndustriesConfig}
 * common config. Read by {@link SeebeckGeneratorBlockEntity}.
 */
public final class SeebeckSourceConfig {
    public final ModConfigSpec.ConfigValue<List<? extends String>> extraHotSources;
    public final ModConfigSpec.ConfigValue<List<? extends String>> extraColdSources;

    public SeebeckSourceConfig(ModConfigSpec.Builder builder) {
        extraHotSources = builder
                .comment(
                        "Additional hot sources for the Seebeck Generator, on top of the #temporalindustries:seebeck_hot_sources tag.",
                        "Each entry is \"block_id=temperature\", e.g. \"minecraft:magma_block=300\". Higher temperature = hotter (lava is 1000).")
                .defineListAllowEmpty("extra_hot_sources", List::of, () -> "modid:block=0", SeebeckSourceConfig::isSourceEntry);

        extraColdSources = builder
                .comment(
                        "Additional cold sources for the Seebeck Generator, on top of the #temporalindustries:seebeck_cold_sources tag.",
                        "Each entry is \"block_id=temperature\", e.g. \"minecraft:frosted_ice=-200\". Lower temperature = colder (powder snow is -900).")
                .defineListAllowEmpty("extra_cold_sources", List::of, () -> "modid:block=0", SeebeckSourceConfig::isSourceEntry);
    }

    private static boolean isSourceEntry(Object o) {
        return o instanceof String s && s.indexOf('=') > 0;
    }
}
