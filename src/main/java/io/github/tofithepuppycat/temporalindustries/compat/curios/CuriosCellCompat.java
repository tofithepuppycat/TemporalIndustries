package io.github.tofithepuppycat.temporalindustries.compat.curios;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICurio;

/**
 * Isolates every reference to Curios' (optional, compileOnly) API for the "cell" curio slot —
 * see {@link CuriosCompat} for the class-isolation rationale this mirrors. Callers must only
 * reach this class from behind a {@code ModList.get().isLoaded("curios")} check.
 */
public final class CuriosCellCompat {
    private CuriosCellCompat() {}

    /** Lets Curios accept the Order/Chaos/Dual Entropy Cells in a curio slot (the "cell" slot,
     * declared in data/temporalindustries/curios/slots/cell.json) — without this, Curios has no
     * way to know these items are wearable curios at all. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(CuriosCapability.ITEM, (stack, ctx) -> (ICurio) () -> stack,
                Registration.ORDER_CELL_ITEM.get(), Registration.CHAOS_CELL_ITEM.get(), Registration.DUAL_ENTROPY_CELL_ITEM.get());
    }

    /** First cell equipped in the "cell" curio slot with spare capacity for the given entropy
     * type, or null. */
    @Nullable
    public static ItemStack findCellInCurioSlot(Player player, EntropyType type) {
        return CuriosApi.getCuriosInventory(player)
                .map(inventory -> {
                    for (SlotResult result : inventory.findCurios("cell")) {
                        ItemStack stack = result.stack();
                        if (stack.getItem() instanceof EntropyReceptacle receptacle && receptacle.hasRoom(stack, type)) {
                            return stack;
                        }
                    }
                    return null;
                })
                .orElse(null);
    }
}
