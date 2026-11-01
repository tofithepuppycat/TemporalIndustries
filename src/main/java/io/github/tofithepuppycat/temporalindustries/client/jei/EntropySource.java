package io.github.tofithepuppycat.temporalindustries.client.jei;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractAcceleratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.DecayAcceleratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.DespawnAcceleratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.EntropyCondenserBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.SchrodingerGeneratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.block.entity.SeebeckGeneratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyEventListener;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyFluids;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ComposterBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One way of obtaining Order or Chaos, for display by {@link EntropySourceRecipeCategory}: either
 * the Entropy Condenser turning orbs into liquid, or something that makes orbs in the first place.
 *
 * @param id names the source in the lang file
 * @param inputs items the source consumes, shown as inputs
 * @param icons items that only illustrate the source and take no part in lookups
 * @param machine the block that performs it, or empty if it just happens in the world
 * @param orbInput whether the source consumes an orb, drawn in place of an item
 * @param fluidMb liquid the source's smallest yield of orbs condenses into
 * @param details the lines explaining amount, odds and conditions
 */
public record EntropySource(EntropyType type, String id, List<ItemStack> inputs, List<ItemStack> icons,
                            ItemStack machine, boolean orbInput, int fluidMb, List<Component> details) {
    private static final String KEY = "jei.temporalindustries.entropy_source.";

    public Component name() {
        return Component.translatable(KEY + id);
    }

    /** Every source of Order and Chaos, condensing first and then everything that yields orbs. */
    public static List<EntropySource> all() {
        List<EntropySource> sources = new ArrayList<>();
        for (EntropyType type : EntropyType.values()) {
            sources.add(new EntropySource(type, "condenser", List.of(), List.of(),
                    Registration.ENTROPY_CONDENSER_ITEM.get().getDefaultInstance(), true,
                    EntropyFluids.toMillibuckets(1), List.of(
                    Component.translatable(KEY + "condenser.detail", EntropyFluids.MB_PER_UNIT),
                    Component.translatable(KEY + "fe", EntropyCondenserBlockEntity.FE_PER_UNIT))));
        }

        addOrder(sources);
        addChaos(sources);
        addBoth(sources);
        return sources;
    }

    private static void addOrder(List<EntropySource> sources) {
        EntropyType order = EntropyType.ORDER;
        sources.add(world(order, "obsidian", Items.OBSIDIAN, EntropyEventListener.OBSIDIAN_ORDER));
        sources.add(world(order, "basalt", Items.BASALT, EntropyEventListener.BASALT_ORDER));
        sources.add(world(order, "cobblestone", Items.COBBLESTONE, EntropyEventListener.COBBLESTONE_ORDER));
        sources.add(world(order, "crop_growth", Items.WHEAT, EntropyEventListener.CROP_GROWTH_ORDER,
                EntropyEventListener.CROP_GROWTH_ORDER_CHANCE));
        sources.add(world(order, "item_despawn", Items.CLOCK, EntropyEventListener.ITEM_DESPAWN_ORDER));
        sources.add(world(order, "compost", Items.COMPOSTER, EntropyEventListener.COMPOST_HARVEST_ORDER));
        sources.add(world(order, "brewing", Items.BREWING_STAND, EntropyEventListener.POTION_BREWED_ORDER));
        sources.add(world(order, "passive_death", Items.BEEF, EntropyEventListener.PASSIVE_MOB_DEATH_ORDER,
                EntropyEventListener.PASSIVE_MOB_DEATH_ORDER_CHANCE));

        List<ItemStack> simplified = EntropyEventListener.SIMPLIFICATION_RESULTS.stream().map(Item::getDefaultInstance).toList();
        sources.add(new EntropySource(order, "smelting", List.of(), simplified, ItemStack.EMPTY, false,
                EntropyFluids.toMillibuckets(1), List.of(
                Component.translatable(KEY + "smelting.detail"),
                Component.translatable(KEY + "chance_each", percent(EntropyEventListener.SMELT_ORDER_CHANCE)))));

        sources.add(new EntropySource(order, "despawn_accelerator", List.of(), List.of(),
                Registration.DESPAWN_ACCELERATOR_ITEM.get().getDefaultInstance(), false,
                EntropyFluids.toMillibuckets(DespawnAcceleratorBlockEntity.ORDER_PER_ITEM), List.of(
                Component.translatable(KEY + "despawn_accelerator.detail"),
                Component.translatable(KEY + "orbs_per_item", DespawnAcceleratorBlockEntity.ORDER_PER_ITEM,
                        seconds(DespawnAcceleratorBlockEntity.PROCESS_TIME)))));
    }

    private static void addChaos(List<EntropySource> sources) {
        EntropyType chaos = EntropyType.CHAOS;
        sources.add(world(chaos, "player_death", Items.PLAYER_HEAD, EntropyEventListener.PLAYER_DEATH_CHAOS));
        sources.add(world(chaos, "hostile_death", Items.ZOMBIE_HEAD, EntropyEventListener.HOSTILE_MOB_DEATH_CHAOS));
        sources.add(world(chaos, "splash_potion", Items.SPLASH_POTION, EntropyEventListener.SPLASH_POTION_CHAOS));
        sources.add(world(chaos, "lingering_potion", Items.LINGERING_POTION, EntropyEventListener.LINGERING_POTION_CHAOS));
        sources.add(world(chaos, "lightning", Items.LIGHTNING_ROD, EntropyEventListener.LIGHTNING_CHAOS));
        sources.add(new EntropySource(chaos, "explosion", List.of(), List.of(Items.TNT.getDefaultInstance()),
                ItemStack.EMPTY, false, EntropyFluids.toMillibuckets(1), List.of(
                Component.translatable(KEY + "explosion.detail", EntropyEventListener.BLOCKS_PER_EXPLOSION_CHAOS,
                        EntropyEventListener.MAX_EXPLOSION_CHAOS))));
        sources.add(world(chaos, "leaf_decay", Items.OAK_LEAVES, EntropyEventListener.LEAF_DECAY_CHAOS,
                EntropyEventListener.LEAF_DECAY_CHAOS_CHANCE));

        List<ItemStack> compostables = ComposterBlock.COMPOSTABLES.keySet().stream()
                .map(item -> new ItemStack(item.asItem())).toList();
        sources.add(new EntropySource(chaos, "decay_accelerator", compostables, List.of(),
                Registration.DECAY_ACCELERATOR_ITEM.get().getDefaultInstance(), false,
                EntropyFluids.toMillibuckets(DecayAcceleratorBlockEntity.MIN_CHAOS), List.of(
                Component.translatable(KEY + "decay_accelerator.detail"),
                Component.translatable(KEY + "orbs_range", DecayAcceleratorBlockEntity.MIN_CHAOS,
                        DecayAcceleratorBlockEntity.MAX_CHAOS,
                        seconds(AbstractAcceleratorBlockEntity.PROCESS_TIME_TICKS)))));
    }

    private static void addBoth(List<EntropySource> sources) {
        // Every pulse is one orb of a random type, so each type gets half of the pulses.
        double schrodingerRate = SchrodingerGeneratorBlockEntity.CHS_PER_INTERVAL * 20.0
                / SchrodingerGeneratorBlockEntity.CHS_INTERVAL_TICKS / 2.0;
        for (EntropyType type : EntropyType.values()) {
            sources.add(new EntropySource(type, "schrodinger", List.of(), List.of(),
                    Registration.SCHRODINGER_GENERATOR_ITEM.get().getDefaultInstance(), false,
                    EntropyFluids.toMillibuckets(1), List.of(
                    Component.translatable(KEY + "schrodinger.detail"),
                    Component.translatable(KEY + "rate", rate(schrodingerRate)))));
        }

        double minRate = SeebeckGeneratorBlockEntity.MIN_ENTROPY_PER_INTERVAL * 20.0 / SeebeckGeneratorBlockEntity.ENTROPY_INTERVAL_TICKS;
        double maxRate = SeebeckGeneratorBlockEntity.MAX_ENTROPY_PER_INTERVAL * 20.0 / SeebeckGeneratorBlockEntity.ENTROPY_INTERVAL_TICKS;
        for (EntropyType type : EntropyType.values()) {
            sources.add(new EntropySource(type, "seebeck", List.of(), List.of(),
                    Registration.SEEBECK_GENERATOR_ITEM.get().getDefaultInstance(), false,
                    EntropyFluids.toMillibuckets(1), List.of(
                    Component.translatable(KEY + "seebeck." + type.getSerializedName()),
                    Component.translatable(KEY + "rate_range", rate(minRate), rate(maxRate)))));
        }
    }

    private static EntropySource world(EntropyType type, String id, Item icon, int orbs) {
        return world(type, id, icon, orbs, 1.0F);
    }

    private static EntropySource world(EntropyType type, String id, Item icon, int orbs, float chance) {
        List<Component> details = new ArrayList<>();
        details.add(Component.translatable(KEY + (orbs == 1 ? "orb" : "orbs"), orbs));
        if (chance < 1.0F) details.add(Component.translatable(KEY + "chance", percent(chance)));
        return new EntropySource(type, id, List.of(), List.of(icon.getDefaultInstance()), ItemStack.EMPTY, false,
                EntropyFluids.toMillibuckets(orbs), details);
    }

    private static String percent(float chance) {
        return Math.round(chance * 100.0F) + "%";
    }

    private static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.0f", ticks / 20.0);
    }

    private static String rate(double perSecond) {
        return String.format(Locale.ROOT, "%.2f", perSecond);
    }
}
