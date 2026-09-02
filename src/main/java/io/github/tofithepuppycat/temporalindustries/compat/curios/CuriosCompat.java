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

/**
 * Isolates every reference to Curios' (optional, compileOnly) API in one class, so that class is
 * the only thing whose loading/verification would fail if Curios isn't installed. Callers must
 * only reach this class from behind a {@code ModList.get().isLoaded("curios")} check (see
 * EntropyGlassesOverlay) -- the JVM resolves a class's referenced types lazily, so as long as that
 * check short-circuits first, this class and the Curios types it names are never touched.
 */
public final class CuriosCompat {
    private CuriosCompat() {}

    /** Lets Curios accept the Entropy Glasses in its "head" slot (an external slot other mods
     * provide - opportunistically tagged via data/curios/tags/item/head.json) and the Temporal
     * Anchor in Curios' own built-in "charm" slot (tagged via data/curios/tags/item/charm.json,
     * granted to the player via data/temporalindustries/curios/entities/player.json — unlike the
     * "cell" slot in {@link CuriosCellCompat}, "charm" is a slot type Curios already defines, so
     * we don't declare our own slot type for it) — neither item is vanilla armor, so without this
     * Curios has no way to know they're wearable curios at all. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(CuriosCapability.ITEM, (stack, ctx) -> (ICurio) () -> stack,
                Registration.ENTROPY_GLASSES_ITEM.get(), Registration.TEMPORAL_ANCHOR_ITEM.get());
    }

    public static boolean isWearingEntropyGlasses(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .map(inventory -> inventory.isEquipped(Registration.ENTROPY_GLASSES_ITEM.get()))
                .orElse(false);
    }

    /** Temporal Anchor equipped in a Curios charm slot, or null - so calibration payment
     * ({@link io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem#findChargedAnchor})
     * and passive order charging ({@link io.github.tofithepuppycat.temporalindustries.entropy.EntropyChargingService})
     * see it the same as one carried in the main inventory or offhand. */
    @Nullable
    public static ItemStack findEquippedTemporalAnchor(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .flatMap(inventory -> inventory.findFirstCurio(Registration.TEMPORAL_ANCHOR_ITEM.get()))
                .map(SlotResult::stack)
                .orElse(null);
    }

    /** Snapshot of every curio slot's contents, for {@link
     * io.github.tofithepuppycat.temporalindustries.device.PlayerSnapshot} to restore on a Temporal
     * Anchor rewind — without this, a second anchor equipped in a curio slot after calibration
     * would survive a "Rewind All" untouched, letting a player duplicate Temporal Anchors instead
     * of losing them like the rest of their inventory does. */
    @Nullable
    public static ListTag saveCurios(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .map(inventory -> inventory.saveInventory(false))
                .orElse(null);
    }

    public static void loadCurios(Player player, ListTag data) {
        CuriosApi.getCuriosInventory(player).ifPresent(inventory -> inventory.loadInventory(data));
    }
}
