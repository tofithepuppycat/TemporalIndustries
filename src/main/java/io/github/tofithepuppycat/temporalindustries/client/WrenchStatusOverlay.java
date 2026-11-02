package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.WrenchConfigurable;
import io.github.tofithepuppycat.temporalindustries.item.WrenchItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * While a {@link WrenchItem} is held, shows above the hotbar which block it is configuring, what
 * that block is linked to and what the next click will do.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class WrenchStatusOverlay {
    private static final String PREFIX = "item.temporalindustries.wrench.status.";
    private static final int BOTTOM_OFFSET = 82;
    private static final int LINE_HEIGHT = 10;

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Post event) {
        if (!event.getName().equals(VanillaGuiLayers.HOTBAR)) return;

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null || minecraft.options.hideGui) return;

        ItemStack wrench = holdingWrench(player);
        if (wrench == null) return;

        List<Component> lines = buildLines(level, wrench);
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        int centre = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() - BOTTOM_OFFSET - (lines.size() - 1) * LINE_HEIGHT;
        for (Component line : lines) {
            graphics.drawCenteredString(font, line, centre, y, 0xFFFFFF);
            y += LINE_HEIGHT;
        }
    }

    private static List<Component> buildLines(Level level, ItemStack wrench) {
        List<Component> lines = new ArrayList<>();
        BlockPos selected = WrenchItem.getSelected(wrench, level);
        if (selected == null) {
            lines.add(Component.translatable(PREFIX + "none").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable(PREFIX + "hint_select").withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }
        if (!(level.getBlockEntity(selected) instanceof WrenchConfigurable configurable)) {
            lines.add(Component.translatable(PREFIX + "missing").withStyle(ChatFormatting.RED));
            return lines;
        }

        lines.add(Component.translatable(PREFIX + "configuring", level.getBlockState(selected).getBlock().getName())
                .withStyle(ChatFormatting.YELLOW));
        List<BlockPos> inputs = configurable.wrenchInputs();
        List<BlockPos> outputs = configurable.wrenchOutputs();
        if (inputs.isEmpty() && outputs.isEmpty()) {
            lines.add(Component.translatable(PREFIX + "no_links").withStyle(ChatFormatting.GRAY));
        } else {
            if (!inputs.isEmpty()) lines.add(linkLine(level, "inputs", inputs, ChatFormatting.RED));
            if (!outputs.isEmpty()) lines.add(linkLine(level, "outputs", outputs, ChatFormatting.AQUA));
        }
        lines.add(Component.translatable(PREFIX + "hint_link").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** "Inputs (3): Tank x2, Decay Accelerator" with equal blocks grouped. */
    private static Component linkLine(Level level, String role, List<BlockPos> links, ChatFormatting colour) {
        Map<Component, Integer> counts = new LinkedHashMap<>();
        for (BlockPos pos : links) {
            counts.merge(level.getBlockState(pos).getBlock().getName(), 1, Integer::sum);
        }
        MutableComponent line = Component.translatable(PREFIX + role, links.size()).append(": ").withStyle(colour);
        boolean first = true;
        for (Map.Entry<Component, Integer> entry : counts.entrySet()) {
            if (!first) line.append(", ");
            first = false;
            line.append(entry.getKey().copy().withStyle(ChatFormatting.WHITE));
            if (entry.getValue() > 1) line.append(Component.literal(" x" + entry.getValue()).withStyle(ChatFormatting.WHITE));
        }
        return line;
    }

    private static ItemStack holdingWrench(Player player) {
        if (player.getMainHandItem().getItem() instanceof WrenchItem) return player.getMainHandItem();
        if (player.getOffhandItem().getItem() instanceof WrenchItem) return player.getOffhandItem();
        return null;
    }
}
