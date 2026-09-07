package io.github.tofithepuppycat.temporalindustries.client;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Client-side cache of every loot table id known to the server, for LootGeneratorScreen's search-bar autocomplete. */
public final class LootTableSuggestionsClientState {
    private static List<ResourceLocation> lootTables = List.of();

    private LootTableSuggestionsClientState() {}

    public static void updateFromServer(List<ResourceLocation> newLootTables) {
        lootTables = newLootTables;
    }

    public static List<ResourceLocation> getLootTables() {
        return lootTables;
    }
}
