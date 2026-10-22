package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Map;

/**
 * Reads the items stored in a block entity's saved data without instantiating it. Any compound
 * shaped like a serialized item stack counts, wherever it sits, so vanilla and modded containers are
 * covered alike, and stacks nested inside other stacks (shulker boxes, bundles) are counted too.
 */
public final class ContainerContents {
    private ContainerContents() {}

    /** Adds sign times every item count found in tag to totals. */
    public static void tally(Tag tag, Map<Item, Long> totals, int sign) {
        if (tag instanceof CompoundTag compound) {
            Item item = stackItem(compound);
            if (item != null) {
                totals.merge(item, (long) sign * compound.getInt("count"), Long::sum);
                if (compound.contains("components", Tag.TAG_COMPOUND)) {
                    tally(compound.getCompound("components"), totals, sign);
                }
                return;
            }
            for (String key : compound.getAllKeys()) {
                tally(compound.get(key), totals, sign);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                tally(element, totals, sign);
            }
        }
    }

    private static Item stackItem(CompoundTag compound) {
        if (!compound.contains("id", Tag.TAG_STRING) || !compound.contains("count", Tag.TAG_ANY_NUMERIC)) return null;
        ResourceLocation id = ResourceLocation.tryParse(compound.getString("id"));
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }
}
