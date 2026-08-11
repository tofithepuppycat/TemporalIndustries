package io.github.tofithepuppycat.temporalindustries.energy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code data/<namespace>/energy_costs/*.json} files, each shaped like:
 * <pre>{@code
 * {
 *   "items": { "minecraft:diamond": 500 },
 *   "tags":  { "c:ingots": 55, "#minecraft:logs": 6 }
 * }
 * }</pre>
 * Only the raw ids are parsed here (see {@link ItemEnergyCosts#parseEntries}); resolving tag
 * membership requires the tag registry to be fully loaded, which isn't guaranteed during a reload
 * listener's apply() phase, so that (and the recipe-based propagation, and the modpack config
 * override file) happens later in {@link ItemEnergyCosts#compute}.
 */
public class EnergyCostReloadListener extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String DIRECTORY = "energy_costs";

    public EnergyCostReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> object, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<ItemEnergyCosts.RawEntry> entries = new ArrayList<>();

        for (Map.Entry<ResourceLocation, JsonElement> fileEntry : object.entrySet()) {
            if (!fileEntry.getValue().isJsonObject()) continue;
            entries.addAll(ItemEnergyCosts.parseEntries(fileEntry.getValue().getAsJsonObject()));
        }

        ItemEnergyCosts.setRawEntries(entries);
        LOGGER.info("[{}] Loaded {} raw item energy cost entries from {} file(s)",
                TemporalIndustries.MODID, entries.size(), object.size());
    }
}
