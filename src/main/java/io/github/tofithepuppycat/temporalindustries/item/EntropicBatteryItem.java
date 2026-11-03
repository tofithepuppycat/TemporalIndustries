package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.block.entity.EntropicBatteryBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.ShortEnergyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** The Entropic Battery item, which lists the energy and entropy its stored block data holds. */
public class EntropicBatteryItem extends BlockItem {
    public EntropicBatteryItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();

        int energy = tag.getInt("Energy");
        int order = tag.getCompound("OrderTank").getInt("amount");
        int chaos = tag.getCompound("ChaosTank").getInt("amount");
        tooltip.add(stored("block.temporalindustries.entropic_battery.stored_energy", ShortEnergyDisplay.formatOutOf(energy, EntropicBatteryBlockEntity.ENERGY_CAPACITY)));
        tooltip.add(stored("block.temporalindustries.entropic_battery.stored_order", outOf(order)));
        tooltip.add(stored("block.temporalindustries.entropic_battery.stored_chaos", outOf(chaos)));
    }

    private static String outOf(int amount) {
        return EntropyDisplay.formatFluid(amount) + "/" + EntropyDisplay.formatFluid(EntropicBatteryBlockEntity.TANK_CAPACITY);
    }

    private static Component stored(String key, String amount) {
        return EntropyDisplay.colorTokens(Component.translatable(key, amount).getString(), ChatFormatting.GRAY);
    }
}
