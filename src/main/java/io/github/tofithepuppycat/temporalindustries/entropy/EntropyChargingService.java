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
 * Passively trickles ORD out of any Order Cell / Dual Entropy Cell carried in a player's main
 * inventory, offhand, or Cell curio slot into chargeable items carried the same way — currently
 * Temporal Glue (restores durability) and the Temporal Anchor (fills its order bar). Ticked once
 * per {@link #CHARGE_INTERVAL_TICKS} from {@link io.github.tofithepuppycat.temporalindustries.device.TemporalChangeListener}'s
 * existing flush loop. Mirrors the direct contents-mutation style of
 * {@link io.github.tofithepuppycat.temporalindustries.block.entity.CrudeEntropyCondenserBlockEntity#drainCell()}.
 */
public final class EntropyChargingService {
    public static final int CHARGE_INTERVAL_TICKS = 20;
    private static final int CHARGE_RATE = 2; // raw order per interval per target
    private static final int DURABILITY_PER_ORDER = 10; // 10 raw (1.0 order) repairs 1 durability point

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

    private static void collectCell(ItemStack stack, List<ItemStack> cells) {
        if (stack.getItem() instanceof EntropyCellItem cell && cell.accepts(EntropyType.ORDER)
                && EntropyCellItem.getContents(stack).amount() > 0) {
            cells.add(stack);
        } else if (stack.getItem() instanceof DualEntropyCellItem && DualEntropyCellItem.getContents(stack).order() > 0) {
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
        int neededRaw = stack.getDamageValue() * DURABILITY_PER_ORDER - progress;
        if (neededRaw <= 0) return;

        int drained = drain(cells, Math.min(CHARGE_RATE, neededRaw));
        if (drained <= 0) return;

        progress += drained;
        int durabilityGained = progress / DURABILITY_PER_ORDER;
        progress %= DURABILITY_PER_ORDER;
        if (durabilityGained > 0) {
            stack.setDamageValue(Math.max(0, stack.getDamageValue() - durabilityGained));
        }
        stack.set(Registration.GLUE_CHARGE_PROGRESS.get(), new BottleContents(progress));
    }

    private static void chargeAnchor(ItemStack stack, List<ItemStack> cells) {
        int order = TemporalAnchorItem.getOrder(stack);
        int room = TemporalAnchorItem.MAX_ORDER - order;
        if (room <= 0) return;

        int drained = drain(cells, Math.min(CHARGE_RATE, room));
        if (drained <= 0) return;

        TemporalAnchorItem.setOrder(stack, order + drained);
    }

    /** Pulls up to amount raw order out of cells, in order, mutating each cell's stored contents
     * in place. Returns how much was actually drained. */
    private static int drain(List<ItemStack> cells, int amount) {
        int remaining = amount;
        for (ItemStack cell : cells) {
            if (remaining <= 0) break;
            remaining -= drainCell(cell, remaining);
        }
        return amount - remaining;
    }

    private static int drainCell(ItemStack stack, int amount) {
        if (amount <= 0) return 0;
        if (stack.getItem() instanceof EntropyCellItem) {
            BottleContents contents = EntropyCellItem.getContents(stack);
            int drained = Math.min(amount, contents.amount());
            if (drained <= 0) return 0;
            stack.set(Registration.BOTTLE_CONTENTS.get(), new BottleContents(contents.amount() - drained));
            return drained;
        } else if (stack.getItem() instanceof DualEntropyCellItem) {
            EntropyContents contents = DualEntropyCellItem.getContents(stack);
            int drained = Math.min(amount, contents.order());
            if (drained <= 0) return 0;
            stack.set(Registration.ENTROPY_CONTENTS.get(), contents.with(EntropyType.ORDER, contents.order() - drained));
            return drained;
        }
        return 0;
    }
}
