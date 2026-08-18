package io.github.tofithepuppycat.temporalindustries.config;

import io.github.tofithepuppycat.temporalindustries.block.entity.SeebeckSourceConfig;
import io.github.tofithepuppycat.temporalindustries.energy.EnergyCostConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * The mod's single common config ({@code config/temporalindustries-common.toml}), split into
 * per-feature sections so each feature's settings live together under their own TOML header while
 * still sharing one file and one {@link ModConfigSpec}.
 */
public final class TemporalIndustriesConfig {
    public final EnergyCostConfig energyCosts;
    public final SeebeckSourceConfig seebeckSources;

    private TemporalIndustriesConfig(ModConfigSpec.Builder builder) {
        builder.push("energy_costs");
        energyCosts = new EnergyCostConfig(builder);
        builder.pop();

        builder.push("seebeck_generator");
        seebeckSources = new SeebeckSourceConfig(builder);
        builder.pop();
    }

    public static final TemporalIndustriesConfig INSTANCE;
    public static final ModConfigSpec SPEC;

    static {
        Pair<TemporalIndustriesConfig, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(TemporalIndustriesConfig::new);
        INSTANCE = specPair.getLeft();
        SPEC = specPair.getRight();
    }
}
