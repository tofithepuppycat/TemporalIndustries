package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCompat;
import io.github.tofithepuppycat.temporalindustries.block.entity.AbstractTimelineMachineBlockEntity;
import io.github.tofithepuppycat.temporalindustries.energy.ItemEnergyCosts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * Adds the base timeline jump cost and the entropy it moves to item tooltips (inventory and JEI)
 * while Entropy Glasses are worn.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class JumpCostTooltipHandler {
    private JumpCostTooltipHandler() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !ModList.get().isLoaded("curios") || !CuriosCompat.isWearingEntropyGlasses(player)) return;

        OptionalInt cost = ItemEnergyCosts.getCost(event.getItemStack());
        if (cost.isEmpty()) return;

        long total = AbstractTimelineMachineBlockEntity.scaledPrice(cost.getAsInt()) * event.getItemStack().getCount();
        event.getToolTip().add(Component.translatable("tooltip.temporalindustries.jump_cost",
                EnergyDisplay.format(total)).withStyle(ChatFormatting.YELLOW));
        event.getToolTip().add(Component.translatable("tooltip.temporalindustries.jump_entropy",
                String.format(Locale.ROOT, "%.2f",
                        total / (double) AbstractTimelineMachineBlockEntity.JUMP_FE_PER_ENTROPY_UNIT / 100.0))
                .withStyle(ChatFormatting.GRAY));
    }
}
