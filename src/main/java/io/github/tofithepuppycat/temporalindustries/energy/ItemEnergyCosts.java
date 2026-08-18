package io.github.tofithepuppycat.temporalindustries.energy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.config.TemporalIndustriesConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Holds the resolved energy cost of every known item and is responsible for computing it.
 *
 * <p>Three layers feed the final table, each able to override the previous:
 * <ol>
 *     <li>Base costs, defined in datapacks (see {@link EnergyCostReloadListener}) either per item
 *     or per item tag (so e.g. a single "#c:ingots" entry covers every ingot, vanilla or modded).</li>
 *     <li>The modpack config at {@code config/temporalindustries-common.toml} (see
 *     {@link EnergyCostConfig}), for modpack developers to add or override costs without touching
 *     a datapack.</li>
 *     <li>Recipe-derived costs: any item without a base cost that has a recipe whose ingredients are
 *     all already priced gets a cost equal to the summed ingredient cost divided by the result count.
 *     This repeats in passes so costs cascade through crafting chains, which is what lets modded
 *     items built from known-cost materials get a cost automatically.</li>
 *     <li>Finally, any block item still without a cost (no base entry, no resolvable recipe) falls
 *     back to a flat default so every block ends up priced. The default itself is configurable via
 *     {@code default_block_cost} in the modpack config.</li>
 * </ol>
 * The result is expensive to compute once (every recipe is scanned, possibly in several passes) but
 * cheap to reuse, so it's cached to disk per-world and only recomputed when the recipe set or base
 * costs actually change.
 */
public final class ItemEnergyCosts {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    private static volatile List<RawEntry> rawEntries = List.of();
    private static volatile Map<Item, Integer> resolved = Map.of();

    private ItemEnergyCosts() {}

    // -------------------------------------------------------------------------
    // Public API

    public static OptionalInt getCost(ItemLike item) {
        Integer cost = resolved.get(item.asItem());
        return cost == null ? OptionalInt.empty() : OptionalInt.of(cost);
    }

    public static OptionalInt getCost(ItemStack stack) {
        return stack.isEmpty() ? OptionalInt.empty() : getCost(stack.getItem());
    }

    // -------------------------------------------------------------------------
    // Raw datapack entries (populated by EnergyCostReloadListener)

    sealed interface RawEntry permits ItemEntry, TagEntry {}

    record ItemEntry(ResourceLocation item, int cost) implements RawEntry {}

    record TagEntry(TagKey<Item> tag, int cost) implements RawEntry {}

    static void setRawEntries(List<RawEntry> entries) {
        rawEntries = entries;
    }

    // -------------------------------------------------------------------------
    // Computation

    public static synchronized void compute(MinecraftServer server) {
        UserConfig userConfig = loadUserConfig();
        int defaultBlockCost = userConfig.defaultBlockCost();

        Map<Item, Integer> baseCosts = resolveBaseCosts(userConfig);
        String signature = computeSignature(server, baseCosts, defaultBlockCost);
        Path cacheFile = cacheFile(server);

        Map<Item, Integer> cached = tryLoadCache(cacheFile, signature);
        if (cached != null) {
            resolved = cached;
            LOGGER.info("[{}] Loaded {} cached item energy costs", TemporalIndustries.MODID, cached.size());
            return;
        }

        Map<Item, Integer> costs = new HashMap<>(baseCosts);
        int derived = propagateThroughRecipes(server, costs);
        int defaulted = applyDefaultBlockCost(costs, defaultBlockCost);
        resolved = Map.copyOf(costs);
        LOGGER.info("[{}] Computed {} item energy costs ({} base, {} derived from recipes, {} defaulted blocks)",
                TemporalIndustries.MODID, resolved.size(), baseCosts.size(), derived, defaulted);
        saveCache(cacheFile, signature, resolved);
    }

    private static Map<Item, Integer> resolveBaseCosts(UserConfig userConfig) {
        Map<Item, Integer> costs = new HashMap<>();
        applyEntries(costs, rawEntries);              // this mod's built-in datapack defaults
        applyEntries(costs, userConfig.entries());     // modpack overrides always win, applied last
        return costs;
    }

    /** Prices any block item still without a cost after base costs and recipe propagation, so every
     * block ends up priced even if it has no known ingredients (e.g. a naturally generated block with
     * no crafting recipe). Returns how many items were priced this way. */
    private static int applyDefaultBlockCost(Map<Item, Integer> costs, int defaultBlockCost) {
        int defaulted = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof BlockItem && !costs.containsKey(item)) {
                costs.put(item, defaultBlockCost);
                defaulted++;
            }
        }
        return defaulted;
    }

    /** Applies tags first (broad strokes for whole item families), then explicit items, so within
     * a single source an explicit item always wins over a tag-derived default. */
    private static void applyEntries(Map<Item, Integer> costs, List<RawEntry> entries) {
        for (RawEntry entry : entries) {
            if (entry instanceof TagEntry tagEntry) {
                BuiltInRegistries.ITEM.getTag(tagEntry.tag()).ifPresent(holders ->
                        holders.forEach(holder -> costs.put(holder.value(), tagEntry.cost())));
            }
        }
        for (RawEntry entry : entries) {
            if (entry instanceof ItemEntry itemEntry) {
                BuiltInRegistries.ITEM.getOptional(itemEntry.item()).ifPresentOrElse(
                        item -> costs.put(item, itemEntry.cost()),
                        () -> LOGGER.warn("[{}] Unknown item '{}' in energy cost data, skipping",
                                TemporalIndustries.MODID, itemEntry.item()));
            }
        }
    }

    /** Parses the {@code items}/{@code tags} objects shared by both the datapack files and the
     * modpack config file into raw entries. Tag ids may optionally be prefixed with '#'. */
    static List<RawEntry> parseEntries(JsonObject root) {
        List<RawEntry> entries = new ArrayList<>();
        if (root.has("items") && root.get("items").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
                entries.add(new ItemEntry(ResourceLocation.parse(e.getKey()), e.getValue().getAsInt()));
            }
        }
        if (root.has("tags") && root.get("tags").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("tags").entrySet()) {
                String key = e.getKey();
                ResourceLocation id = ResourceLocation.parse(key.startsWith("#") ? key.substring(1) : key);
                entries.add(new TagEntry(TagKey.create(Registries.ITEM, id), e.getValue().getAsInt()));
            }
        }
        return entries;
    }

    // -------------------------------------------------------------------------
    // Modpack config (config/temporalindustries-common.toml, see EnergyCostConfig)

    private record UserConfig(List<RawEntry> entries, int defaultBlockCost) {}

    private static UserConfig loadUserConfig() {
        List<RawEntry> entries = new ArrayList<>();
        EnergyCostConfig config = TemporalIndustriesConfig.INSTANCE.energyCosts;
        parseCostEntries(config.itemCosts.get(), true, entries);
        parseCostEntries(config.tagCosts.get(), false, entries);
        return new UserConfig(entries, config.defaultBlockCost.get());
    }

    /** Parses "id=cost" config lines (see EnergyCostConfig) into raw entries, logging and skipping
     * anything malformed rather than failing the whole config. */
    private static void parseCostEntries(List<? extends String> rawLines, boolean isItem, List<RawEntry> out) {
        for (String line : rawLines) {
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                LOGGER.warn("[{}] Malformed energy cost entry '{}' (expected \"id=cost\"), skipping", TemporalIndustries.MODID, line);
                continue;
            }
            String id = line.substring(0, eq).trim();
            try {
                int cost = Integer.parseInt(line.substring(eq + 1).trim());
                if (isItem) {
                    out.add(new ItemEntry(ResourceLocation.parse(id), cost));
                } else {
                    ResourceLocation tagId = ResourceLocation.parse(id.startsWith("#") ? id.substring(1) : id);
                    out.add(new TagEntry(TagKey.create(Registries.ITEM, tagId), cost));
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[{}] Malformed energy cost entry '{}', skipping", TemporalIndustries.MODID, line, e);
            }
        }
    }

    /** Iteratively assigns a cost to any priceable item whose recipe ingredients are already priced,
     * until a full pass makes no further progress. Returns how many items were priced this way. */
    private static int propagateThroughRecipes(MinecraftServer server, Map<Item, Integer> costs) {
        HolderLookup.Provider registries = server.registryAccess();
        List<RecipeHolder<?>> recipes = server.getRecipeManager().getRecipes().stream()
                .sorted(Comparator.comparing(holder -> holder.id().toString()))
                .toList();

        int totalDerived = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (RecipeHolder<?> holder : recipes) {
                Recipe<?> recipe = holder.value();
                ItemStack result = recipe.getResultItem(registries);
                if (result.isEmpty() || costs.containsKey(result.getItem())) continue;

                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                if (ingredients.isEmpty()) continue;

                long total = 0;
                boolean resolvable = true;
                for (Ingredient ingredient : ingredients) {
                    if (ingredient.isEmpty()) continue;
                    OptionalInt cheapest = cheapestKnownOption(ingredient, costs);
                    if (cheapest.isEmpty()) {
                        resolvable = false;
                        break;
                    }
                    total += cheapest.getAsInt();
                }
                if (!resolvable) continue;

                int perItem = Math.max(1, (int) Math.ceil(total / (double) result.getCount()));
                costs.put(result.getItem(), perItem);
                changed = true;
                totalDerived++;
            }
        }
        return totalDerived;
    }

    private static OptionalInt cheapestKnownOption(Ingredient ingredient, Map<Item, Integer> costs) {
        int best = Integer.MAX_VALUE;
        boolean found = false;
        for (ItemStack option : ingredient.getItems()) {
            Integer cost = costs.get(option.getItem());
            if (cost != null && cost < best) {
                best = cost;
                found = true;
            }
        }
        return found ? OptionalInt.of(best) : OptionalInt.empty();
    }

    // -------------------------------------------------------------------------
    // Cache (per-world, invalidated whenever the recipe set or base costs change)

    private static Path cacheFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(TemporalIndustries.MODID + "_energy_costs.json");
    }

    private static String computeSignature(MinecraftServer server, Map<Item, Integer> baseCosts, int defaultBlockCost) {
        List<String> recipeIds = server.getRecipeManager().getRecipes().stream()
                .map(holder -> holder.id().toString())
                .sorted()
                .toList();
        List<String> baseEntries = baseCosts.entrySet().stream()
                .map(e -> BuiltInRegistries.ITEM.getKey(e.getKey()) + "=" + e.getValue())
                .sorted()
                .toList();

        StringBuilder sb = new StringBuilder();
        recipeIds.forEach(sb::append);
        sb.append('|');
        baseEntries.forEach(sb::append);
        sb.append('|').append(defaultBlockCost);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<Item, Integer> tryLoadCache(Path file, String signature) {
        if (!Files.isRegularFile(file)) return null;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("signature") || !root.has("costs")) return null;
            if (!signature.equals(root.get("signature").getAsString())) return null;

            Map<Item, Integer> result = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("costs").entrySet()) {
                BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(e.getKey()))
                        .ifPresent(item -> result.put(item, e.getValue().getAsInt()));
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[{}] Failed to load item energy cost cache, recomputing", TemporalIndustries.MODID, e);
            return null;
        }
    }

    private static void saveCache(Path file, String signature, Map<Item, Integer> costs) {
        try {
            Files.createDirectories(file.getParent());

            JsonObject costsJson = new JsonObject();
            costs.entrySet().stream()
                    .sorted(Comparator.comparing(e -> BuiltInRegistries.ITEM.getKey(e.getKey()).toString()))
                    .forEach(e -> costsJson.addProperty(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue()));

            JsonObject root = new JsonObject();
            root.addProperty("signature", signature);
            root.add("costs", costsJson);

            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("[{}] Failed to save item energy cost cache", TemporalIndustries.MODID, e);
        }
    }
}
