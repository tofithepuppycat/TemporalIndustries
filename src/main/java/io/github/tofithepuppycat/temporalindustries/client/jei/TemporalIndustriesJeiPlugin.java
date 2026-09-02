package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.client.screen.EntropyManipulatorScreen;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.recipe.EntropyManipulatorRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Discovered by JEI via the {@link JeiPlugin} annotation (NeoForge scans annotated classes at
 * mod-load time; nothing here runs, or even loads, when JEI isn't installed). Adds a "Show
 * recipes" click area over the Entropy Manipulator's progress bar (see
 * {@link EntropyManipulatorScreen#PROGRESS_X}) the same way Mekanism's machines do, so hovering
 * it shows the prompt and clicking opens the recipe category built by
 * {@link EntropyManipulatorRecipeCategory}.
 */
@JeiPlugin
public class TemporalIndustriesJeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_UID = ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "jei_plugin");

    // Lazy (via createFromDeferredVanilla) since JEI can instantiate this plugin, running this
    // field's initializer, before NeoForge's RegisterEvent has bound the DeferredHolder below --
    // resolving it eagerly threw "Trying to access unbound value" on startup.
    public static final Supplier<RecipeType<RecipeHolder<EntropyManipulatorRecipe>>> ENTROPY_MANIPULATOR_RECIPE_TYPE =
            RecipeType.createFromDeferredVanilla(Registration.ENTROPY_MANIPULATOR_RECIPE_TYPE);

    /** Not backed by a real vanilla Recipe -- built at plugin-load time from the recipes above; see {@link #buildChains}. */
    public static final RecipeType<EntropyManipulatorChain> ENTROPY_MANIPULATOR_CHAIN_RECIPE_TYPE =
            RecipeType.create(TemporalIndustries.MODID, "entropy_manipulator_chain", EntropyManipulatorChain.class);

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(
                new EntropyManipulatorRecipeCategory(registration.getJeiHelpers().getGuiHelper()),
                new EntropyManipulatorChainRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;

        List<RecipeHolder<EntropyManipulatorRecipe>> recipes =
                level.getRecipeManager().getAllRecipesFor(Registration.ENTROPY_MANIPULATOR_RECIPE_TYPE.get());
        registration.addRecipes(ENTROPY_MANIPULATOR_RECIPE_TYPE.get(), recipes);
        registration.addRecipes(ENTROPY_MANIPULATOR_CHAIN_RECIPE_TYPE, buildChains(recipes));
    }

    /**
     * Groups every non-fluid recipe into connected chains by shared item (e.g. stone/cobblestone/
     * gravel/sand/redstone), each chain walked from a leaf end so items come out in a consistent
     * left-to-right order. Every chain in the current data is a simple path (each item has at most
     * one chaos-typed and one order-typed edge), so a leaf-to-leaf walk following unvisited
     * neighbors is sufficient; it isn't a general graph solver.
     */
    private static List<EntropyManipulatorChain> buildChains(List<RecipeHolder<EntropyManipulatorRecipe>> recipes) {
        record TypedCost(EntropyType type, int cost) {
        }

        Map<Item, Map<Item, TypedCost>> directed = new LinkedHashMap<>();
        Map<Item, LinkedHashSet<Item>> undirected = new LinkedHashMap<>();
        List<Item> insertionOrder = new ArrayList<>();

        for (RecipeHolder<EntropyManipulatorRecipe> holder : recipes) {
            EntropyManipulatorRecipe recipe = holder.value();
            if (recipe.isFluidRecipe()) continue;
            java.util.Optional<Ingredient> inputOpt = recipe.inputItemOpt();
            if (inputOpt.isEmpty()) continue;
            ItemStack[] matching = inputOpt.get().getItems();
            if (matching.length == 0) continue;

            Item from = matching[0].getItem();
            Item to = recipe.result().getItem();
            if (from == to) continue;

            directed.computeIfAbsent(from, k -> new LinkedHashMap<>()).put(to, new TypedCost(recipe.entropyType(), recipe.entropyCost()));
            undirected.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            undirected.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
            if (!insertionOrder.contains(from)) insertionOrder.add(from);
            if (!insertionOrder.contains(to)) insertionOrder.add(to);
        }

        Set<Item> visited = new HashSet<>();
        List<EntropyManipulatorChain> chains = new ArrayList<>();

        for (Item start : insertionOrder) {
            if (visited.contains(start) || undirected.get(start).size() > 1) continue;

            List<Item> path = new ArrayList<>();
            Item current = start;
            Item previous = null;
            while (current != null) {
                path.add(current);
                visited.add(current);
                Item next = null;
                for (Item neighbor : undirected.get(current)) {
                    if (!neighbor.equals(previous) && !visited.contains(neighbor)) {
                        next = neighbor;
                        break;
                    }
                }
                previous = current;
                current = next;
            }
            if (path.size() < 2) continue;

            List<ItemStack> items = path.stream().map(Item::getDefaultInstance).toList();
            List<EntropyManipulatorChain.Step> steps = new ArrayList<>();
            for (int i = 0; i < path.size() - 1; i++) {
                Item a = path.get(i);
                Item b = path.get(i + 1);
                TypedCost forward = directed.getOrDefault(a, Map.of()).get(b);
                TypedCost backward = directed.getOrDefault(b, Map.of()).get(a);
                steps.add(new EntropyManipulatorChain.Step(
                        forward == null ? null : forward.type(), forward == null ? 0 : forward.cost(),
                        backward == null ? null : backward.type(), backward == null ? 0 : backward.cost()));
            }
            chains.add(new EntropyManipulatorChain(items, steps));
        }
        return chains;
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(Registration.ENTROPY_MANIPULATOR_ITEM.get().getDefaultInstance(), ENTROPY_MANIPULATOR_RECIPE_TYPE.get());
        registration.addRecipeCatalyst(Registration.ENTROPY_MANIPULATOR_ITEM.get().getDefaultInstance(), ENTROPY_MANIPULATOR_CHAIN_RECIPE_TYPE);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addRecipeClickArea(EntropyManipulatorScreen.class,
                EntropyManipulatorScreen.PROGRESS_X, EntropyManipulatorScreen.PROGRESS_Y,
                EntropyManipulatorScreen.PROGRESS_WIDTH, EntropyManipulatorScreen.PROGRESS_HEIGHT,
                ENTROPY_MANIPULATOR_RECIPE_TYPE.get());
    }
}
