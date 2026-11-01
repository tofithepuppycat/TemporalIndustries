package io.github.tofithepuppycat.temporalindustries.client.jei;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Entropy Manipulator repairing damaged items, for display by {@link EntropyRepairRecipeCategory}:
 * every item with durability goes in worn and comes out whole. Both lists are in the same order, so
 * a worn item cycles together with its repaired self.
 */
public record EntropyRepair(List<ItemStack> worn, List<ItemStack> repaired) {
    /** The repair of every item that has durability. */
    public static EntropyRepair ofAllDamageableItems() {
        List<ItemStack> worn = new ArrayList<>();
        List<ItemStack> repaired = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack whole = item.getDefaultInstance();
            if (!whole.isDamageableItem()) continue;

            ItemStack damaged = whole.copy();
            damaged.setDamageValue(damaged.getMaxDamage() / 2);
            worn.add(damaged);
            repaired.add(whole);
        }
        return new EntropyRepair(worn, repaired);
    }
}
