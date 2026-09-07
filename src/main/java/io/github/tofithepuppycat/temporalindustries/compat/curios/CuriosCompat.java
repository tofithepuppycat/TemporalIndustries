package io.github.tofithepuppycat.temporalindustries.compat.curios;

import io.github.tofithepuppycat.temporalindustries.Registration;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * Isolates all references to Curios' (optional, compileOnly) API so only this class fails to
 * load if Curios isn't installed. Callers must only reach it from behind a
 * {@code ModList.get().isLoaded("curios")} check.
 */
public final class CuriosCompat {
    private CuriosCompat() {}

    /** Registers the Entropy Glasses ("head" slot) and Temporal Anchor ("charm" slot) as
     * curios, since neither is vanilla armor and Curios has no other way to know they're wearable. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(CuriosCapability.ITEM, (stack, ctx) -> (ICurio) () -> stack,
                Registration.ENTROPY_GLASSES_ITEM.get(), Registration.TEMPORAL_ANCHOR_ITEM.get());
    }

    public static boolean isWearingEntropyGlasses(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .map(inventory -> inventory.isEquipped(Registration.ENTROPY_GLASSES_ITEM.get()))
                .orElse(false);
    }

    /** Temporal Anchor equipped in a Curios charm slot, or null. */
    @Nullable
    public static ItemStack findEquippedTemporalAnchor(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .flatMap(inventory -> inventory.findFirstCurio(Registration.TEMPORAL_ANCHOR_ITEM.get()))
                .map(SlotResult::stack)
                .orElse(null);
    }

    /** Snapshot of every curio slot's contents, used by {@link
     * io.github.tofithepuppycat.temporalindustries.device.PlayerSnapshot} to restore on a Temporal
     * Anchor rewind (otherwise an equipped anchor would survive "Rewind All" and duplicate). */
    @Nullable
    public static ListTag saveCurios(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .map(inventory -> inventory.saveInventory(false))
                .orElse(null);
    }

    /** Empties every curio slot first, since Curios' {@code loadInventory} only fills empty slots
     * and would otherwise silently drop the snapshot for any slot still occupied. */
    public static void loadCurios(Player player, ListTag data) {
        CuriosApi.getCuriosInventory(player).ifPresent(inventory -> {
            for (ICurioStacksHandler handler : inventory.getCurios().values()) {
                IDynamicStackHandler stacks = handler.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    stacks.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
            inventory.loadInventory(data);
        });
    }
}
