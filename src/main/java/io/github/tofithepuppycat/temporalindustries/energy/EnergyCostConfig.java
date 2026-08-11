package io.github.tofithepuppycat.temporalindustries.energy;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Modpack-editable energy cost overrides — the standard NeoForge common config
 * ({@code config/temporalindustries-common.toml}) rather than a hand-rolled file, so it gets the
 * usual TOML comments, validation and hot-reload for free. Read by {@link ItemEnergyCosts}.
 */
public final class EnergyCostConfig {
    public final ModConfigSpec.ConfigValue<List<? extends String>> itemCosts;
    public final ModConfigSpec.ConfigValue<List<? extends String>> tagCosts;
    public final ModConfigSpec.IntValue defaultBlockCost;

    private EnergyCostConfig(ModConfigSpec.Builder builder) {
        itemCosts = builder
                .comment(
                        "Per-item energy cost overrides. Always win over the mod's built-in costs and are re-read every server start.",
                        "Each entry is \"item_id=cost\", e.g. \"minecraft:diamond=500\".")
                .defineListAllowEmpty("item_costs", List::of, () -> "modid:item=0", EnergyCostConfig::isCostEntry);

        tagCosts = builder
                .comment(
                        "Per-tag energy cost overrides, applied to every item in the tag (vanilla or modded).",
                        "Each entry is \"tag_id=cost\", e.g. \"c:ingots=55\" (a leading '#' is optional).")
                .defineListAllowEmpty("tag_costs", List::of, () -> "modid:tag=0", EnergyCostConfig::isCostEntry);

        defaultBlockCost = builder
                .comment("Flat energy cost given to any block item that ends up with no base cost and no resolvable recipe.")
                .defineInRange("default_block_cost", 5, 0, Integer.MAX_VALUE);
    }

    private static boolean isCostEntry(Object o) {
        return o instanceof String s && s.indexOf('=') > 0;
    }

    public static final EnergyCostConfig INSTANCE;
    public static final ModConfigSpec SPEC;

    static {
        Pair<EnergyCostConfig, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(EnergyCostConfig::new);
        INSTANCE = specPair.getLeft();
        SPEC = specPair.getRight();
    }
}
