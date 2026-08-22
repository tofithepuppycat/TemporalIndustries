package io.github.tofithepuppycat.temporalindustries.entropy;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCellCompat;
import io.github.tofithepuppycat.temporalindustries.item.DualEntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.item.EntropyCellItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalAnchorItem;
import io.github.tofithepuppycat.temporalindustries.item.TemporalGlueItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Passively trickles liquid ORD out of any Order Cell / Dual Entropy Cell carried in a player's main
 * inventory, offhand, or Cell curio slot into chargeable items carried the same way - currently
 * Temporal Glue (restores durability) and the Temporal Anchor (fills its order tank). Ticked once
 * per {@link #CHARGE_INTERVAL_TICKS} from {@link io.github.tofithepuppycat.temporalindustries.device.TemporalChangeListener}'s
 * existing flush loop. All amounts are mB, like everywhere else downstream of an orb.
 */
public final class EntropyChargingService {
    public static final int CHARGE_INTERVAL_TICKS = 20;
    private static final int CHARGE_RATE = 100; // mB of order per interval per target
    private static final int MB_PER_DURABILITY = 500; // liquid order spent to repair one durability point

    private EntropyChargingService() {}

    public static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            chargePlayer(player);
        }
    }

    private static void chargePlayer(ServerPlayer player) {
        List<ItemStack> cells = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) collectCell(stack, cells);
        collectCell(player.getOffhandItem(), cells);
        if (ModList.get().isLoaded("curios")) {
            ItemStack curioCell = CuriosCellCompat.findCellInCurioSlot(player, EntropyType.ORDER);
            if (curioCell != null) collectCell(curioCell, cells);
        }
        if (cells.isEmpty()) return;

        for (ItemStack stack : player.getInventory().items) chargeTarget(stack, cells);
        chargeTarget(player.getOffhandItem(), cells);
    }

    /** Only the cells count as a source - the anchor is a receptacle too, but it is a charge target
     * here, not a donor. */
    private static void collectCell(ItemStack stack, List<ItemStack> cells) {
        boolean isCell = stack.getItem() instanceof EntropyCellItem || stack.getItem() instanceof DualEntropyCellItem;
        if (isCell && ((EntropyReceptacle) stack.getItem()).amount(stack, EntropyType.ORDER) > 0) {
            cells.add(stack);
        }
    }

    private static void chargeTarget(ItemStack stack, List<ItemStack> cells) {
        if (stack.getItem() instanceof TemporalGlueItem) {
            chargeGlue(stack, cells);
        } else if (stack.getItem() instanceof TemporalAnchorItem) {
            chargeAnchor(stack, cells);
        }
    }

    private static void chargeGlue(ItemStack stack, List<ItemStack> cells) {
        int progress = stack.getOrDefault(Registration.GLUE_CHARGE_PROGRESS.get(), BottleContents.EMPTY).amount();
        int needed = stack.getDamageValue() * MB_PER_DURABILITY - progress;
        if (needed <= 0) return;

        int drained = drain(cells, Math.min(CHARGE_RATE, needed));
        if (drained <= 0) return;

        progress += drained;
        int durabilityGained = progress / MB_PER_DURABILITY;
        progress %= MB_PER_DURABILITY;
        if (durabilityGained > 0) {
            stack.setDamageValue(Math.max(0, stack.getDamageValue() - durabilityGained));
        }
        stack.set(Registration.GLUE_CHARGE_PROGRESS.get(), new BottleContents(progress));
    }

    private static void chargeAnchor(ItemStack stack, List<ItemStack> cells) {
        int room = TemporalAnchorItem.MAX_ORDER_MB - TemporalAnchorItem.getOrder(stack);
        if (room <= 0) return;

        int drained = drain(cells, Math.min(CHARGE_RATE, room));
        if (drained <= 0) return;

        ((EntropyReceptacle) stack.getItem()).fill(stack, EntropyType.ORDER, drained);
    }

    /** Pulls up to millibuckets of liquid order out of cells, in order, mutating each cell's stored
     * contents in place. Returns how much was actually drained. */
    private static int drain(List<ItemStack> cells, int millibuckets) {
        int remaining = millibuckets;
        for (ItemStack cell : cells) {
            if (remaining <= 0) break;
            remaining -= ((EntropyReceptacle) cell.getItem()).drain(cell, EntropyType.ORDER, remaining);
        }
        return millibuckets - remaining;
    }
}
