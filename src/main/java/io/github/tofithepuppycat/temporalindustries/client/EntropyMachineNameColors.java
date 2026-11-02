package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Colours the tooltip name of the mod's machines and entropy items by the entropy they deal in: Order or Chaos for
 * those tied to one, a gradient from Order to Chaos for those that work with both. A few items
 * have a colour of their own.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class EntropyMachineNameColors {
    /** The name colour of each item; a null value stands for those that relate to both types. */
    private static Map<Item, Integer> machines;

    private EntropyMachineNameColors() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!isEntropyMachine(stack.getItem())) return;

        List<Component> tooltip = event.getToolTip();
        if (tooltip.isEmpty()) return;
        Integer color = machines().get(stack.getItem());
        Style base = tooltip.get(0).getStyle();
        String name = tooltip.get(0).getString();
        tooltip.set(0, color == null ? gradient(name, base) : Component.literal(name).withStyle(base.withColor(color)));
    }

    private static boolean isEntropyMachine(Item item) {
        return machines().containsKey(item);
    }

    private static Map<Item, Integer> machines() {
        if (machines == null) {
            Map<Item, Integer> map = new HashMap<>();
            for (Item item : List.of(Registration.ORDER_PYLON_ITEM.get(), Registration.DESPAWN_ACCELERATOR_ITEM.get(),
                    Registration.TEMPORAL_ANCHOR_ITEM.get(), Registration.ORDER_CELL_ITEM.get(),
                    Registration.RESONANT_ORDER_CELL_ITEM.get(), Registration.ORDER_BUCKET_ITEM.get(),
                    Registration.ANTI_TEMPORAL_GLUE_ITEM.get())) {
                map.put(item, EntropyType.ORDER.color());
            }
            for (Item item : List.of(Registration.CHAOS_PYLON_ITEM.get(), Registration.DECAY_ACCELERATOR_ITEM.get(),
                    Registration.WITHERGENIC_REACTOR_ITEM.get(), Registration.LOOT_GENERATOR_ITEM.get(),
                    Registration.CHAOS_CELL_ITEM.get(), Registration.RESONANT_CHAOS_CELL_ITEM.get(),
                    Registration.CHAOS_BUCKET_ITEM.get(),
                    Registration.TEMPORAL_GLUE_ITEM.get(), Registration.ECHO_PROJECTOR_ITEM.get(),
                    Registration.ECHO_RECORD_ITEM.get(), Registration.CHRONO_REMOTE_CONTROL_ITEM.get())) {
                map.put(item, EntropyType.CHAOS.color());
            }
            map.put(Registration.TIME_DILATOR_ITEM.get(), ChatFormatting.GOLD.getColor());
            for (Item item : List.of(Registration.ENTROPY_CONDENSER_ITEM.get(), Registration.ENTROPIC_BATTERY_ITEM.get(),
                    Registration.ENTROPY_BALANCER_ITEM.get(), Registration.ENTROPY_MANIPULATOR_ITEM.get(),
                    Registration.ENTROPIC_PYLON_ITEM.get(), Registration.SCHRODINGER_GENERATOR_ITEM.get(),
                    Registration.SEEBECK_GENERATOR_ITEM.get(), Registration.CHRONODIAL_ITEM.get(),
                    Registration.CHRONOSPHERE_ITEM.get(), Registration.CREATIVE_CHRONOSPHERE_ITEM.get(),
                    Registration.CHRONOVAULT_ITEM.get(), Registration.DUAL_ENTROPY_CELL_ITEM.get(),
                    Registration.RESONANT_DUAL_ENTROPY_CELL_ITEM.get(), Registration.ENTROPY_GLASSES_ITEM.get())) {
                map.put(item, null);
            }
            machines = map;
        }
        return machines;
    }

    /** name with its letters shading from the Order colour to the Chaos colour. */
    private static MutableComponent gradient(String name, Style base) {
        MutableComponent result = Component.empty();
        int last = Math.max(1, name.length() - 1);
        int order = EntropyType.ORDER.color();
        int chaos = EntropyType.CHAOS.color();
        for (int i = 0; i < name.length(); i++) {
            float t = i / (float) last;
            result.append(Component.literal(String.valueOf(name.charAt(i)))
                    .withStyle(base.withColor(blend(order, chaos, t))));
        }
        return result;
    }

    private static int blend(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return (r << 16) | (g << 8) | b;
    }
}
