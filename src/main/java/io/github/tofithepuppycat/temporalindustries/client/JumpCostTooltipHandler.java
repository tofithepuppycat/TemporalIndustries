package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCompat;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.OptionalInt;

/**
 * Adds the base timeline jump cost to block item tooltips (inventory and JEI) while Entropy Glasses
 * are worn.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class JumpCostTooltipHandler {
    private JumpCostTooltipHandler() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof BlockItem)) return;

        Player player = Minecraft.getInstance().player;
        if (player == null || !ModList.get().isLoaded("curios") || !CuriosCompat.isWearingEntropyGlasses(player)) return;

        OptionalInt cost = ItemEnergyCosts.getCost(event.getItemStack());
        if (cost.isEmpty()) return;

        event.getToolTip().add(Component.translatable("tooltip.temporalindustries.jump_cost",
                EnergyDisplay.format(cost.getAsInt())).withStyle(ChatFormatting.YELLOW));
    }
}
