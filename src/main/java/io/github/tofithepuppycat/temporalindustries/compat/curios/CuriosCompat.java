package io.github.tofithepuppycat.temporalindustries.compat.curios;

import io.github.tofithepuppycat.temporalindustries.Registration;
import net.minecraft.world.entity.player.Player;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Isolates every reference to Curios' (optional, compileOnly) API in one class, so that class is
 * the only thing whose loading/verification would fail if Curios isn't installed. Callers must
 * only reach this class from behind a {@code ModList.get().isLoaded("curios")} check (see
 * EntropyGlassesOverlay) -- the JVM resolves a class's referenced types lazily, so as long as that
 * check short-circuits first, this class and the Curios types it names are never touched.
 */
public final class CuriosCompat {
    private CuriosCompat() {}

    public static boolean isWearingEntropyGlasses(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .map(inventory -> inventory.isEquipped(Registration.ENTROPY_GLASSES_ITEM.get()))
                .orElse(false);
    }
}
