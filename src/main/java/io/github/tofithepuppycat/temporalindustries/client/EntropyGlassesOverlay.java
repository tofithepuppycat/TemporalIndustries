package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCellCompat;
import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCompat;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyReceptacle;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/** Draws the looked-at block's entropy info to the right of the crosshair while Entropy Glasses
 * are worn. Sneaking also draws the equipped cells' Order/Chaos fill levels to the left. */
public final class EntropyGlassesOverlay implements LayeredDraw.Layer {
    public static final EntropyGlassesOverlay INSTANCE = new EntropyGlassesOverlay();

    /** Mirrors the bidirectional balance bar drawn by ChronosphereScreen/ChronovaultScreen. */
    private static final int BAR_WIDTH = 60;
    private static final int BAR_HEIGHT = 8;
    private static final int LINE_GAP = 2;
    private static final int CROSSHAIR_GAP = 12;
    private static final int COLOR_ORDER_BAR = 0xFF000000 | EntropyType.ORDER.color();
    private static final int COLOR_CHAOS_BAR = 0xFF000000 | EntropyType.CHAOS.color();

    private EntropyGlassesOverlay() {}

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) return;

        Player player = minecraft.player;
        if (player == null || !isWearingEntropyGlasses(player)) return;

        if (player.isCrouching()) {
            renderCellStatus(guiGraphics, minecraft.font, player);
        }

        renderBlockInfo(guiGraphics, minecraft);
    }

    private void renderBlockInfo(GuiGraphics guiGraphics, Minecraft minecraft) {
        Level level = minecraft.level;
        if (level == null || !(minecraft.hitResult instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) return;

        BlockEntity blockEntity = level.getBlockEntity(blockHit.getBlockPos());
        if (!(blockEntity instanceof EntropyInfoProvider provider)) return;

        List<Component> lines = provider.getEntropyTooltip();
        boolean hasBalance = provider.hasEntropyBalance();
        if (lines.isEmpty() && !hasBalance) return;

        Font font = minecraft.font;
        Component balanceText = hasBalance
                ? entropyBalanceText(provider.getEntropyBalance(), provider.getEntropyBalanceMax())
                : null;
        Component rateText = hasBalance ? entropyRateText(provider.getEntropyRatePerSecond()) : null;

        int contentWidth = BAR_WIDTH;
        for (Component line : lines) contentWidth = Math.max(contentWidth, font.width(line));
        if (balanceText != null) contentWidth = Math.max(contentWidth, font.width(balanceText));
        if (rateText != null) contentWidth = Math.max(contentWidth, font.width(rateText));

        int contentHeight = lines.size() * (font.lineHeight + LINE_GAP);
        if (hasBalance) contentHeight += BAR_HEIGHT + 3 + 2 * (font.lineHeight + LINE_GAP);
        contentHeight -= LINE_GAP;

        int left = guiGraphics.guiWidth() / 2 + CROSSHAIR_GAP;
        int top = guiGraphics.guiHeight() / 2 - contentHeight / 2;

        TooltipRenderUtil.renderTooltipBackground(guiGraphics, left, top, contentWidth, contentHeight, 0);

        int y = top;
        for (Component line : lines) {
            y = drawLine(guiGraphics, font, line, left, y);
        }

        if (hasBalance) {
            int entropy = provider.getEntropyBalance();
            int max = provider.getEntropyBalanceMax();

            renderEntropyBar(guiGraphics, left, y, entropy, max);
            y += BAR_HEIGHT + 3;

            y = drawLine(guiGraphics, font, balanceText, left, y);
            drawLine(guiGraphics, font, rateText, left, y);
        }
    }

    private static boolean isWearingEntropyGlasses(Player player) {
        return ModList.get().isLoaded("curios") && CuriosCompat.isWearingEntropyGlasses(player);
    }

    /** Panel of equipped cells' Order/Chaos fill levels, drawn left of the crosshair while sneaking. */
    private static void renderCellStatus(GuiGraphics guiGraphics, Font font, Player player) {
        if (!ModList.get().isLoaded("curios")) return;

        List<Component> lines = cellStatusLines(CuriosCellCompat.cellsInCurioSlots(player));
        if (lines.isEmpty()) return;

        int contentWidth = 0;
        for (Component line : lines) contentWidth = Math.max(contentWidth, font.width(line));
        int contentHeight = lines.size() * (font.lineHeight + LINE_GAP) - LINE_GAP;

        int right = guiGraphics.guiWidth() / 2 - CROSSHAIR_GAP;
        int left = right - contentWidth;
        int top = guiGraphics.guiHeight() / 2 - contentHeight / 2;

        TooltipRenderUtil.renderTooltipBackground(guiGraphics, left, top, contentWidth, contentHeight, 0);

        int y = top;
        for (Component line : lines) {
            y = drawLine(guiGraphics, font, line, left, y);
        }
    }

    private static List<Component> cellStatusLines(List<ItemStack> cells) {
        List<Component> lines = new ArrayList<>();
        for (ItemStack stack : cells) {
            if (!(stack.getItem() instanceof EntropyReceptacle receptacle)) continue;

            lines.add(stack.getHoverName().copy().withStyle(ChatFormatting.WHITE));
            for (EntropyType type : EntropyType.values()) {
                if (!receptacle.accepts(type)) continue;
                ChatFormatting color = type == EntropyType.ORDER ? ChatFormatting.WHITE : ChatFormatting.DARK_PURPLE;
                lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.liquid",
                                EntropyDisplay.formatFluid(receptacle.amount(stack, type)), EntropyDisplay.formatFluid(receptacle.capacity(type)))
                        .withStyle(color).append(EntropyDisplay.unit(type)));
            }
        }
        return lines;
    }

    private static int drawLine(GuiGraphics guiGraphics, Font font, Component line, int x, int y) {
        guiGraphics.drawString(font, line, x, y, 0xFFFFFF);
        return y + font.lineHeight + LINE_GAP;
    }

    /** Bidirectional order<->chaos balance bar: fills from the center tick outward, white toward
     * order (below the midpoint) and dark purple toward chaos (above it). */
    private static void renderEntropyBar(GuiGraphics guiGraphics, int barX, int barY, int entropy, int max) {
        guiGraphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, 0xFF000000);

        int mid = barX + BAR_WIDTH / 2;
        int half = BAR_WIDTH / 2 - 1;
        float balance = max > 0 ? (entropy - max / 2f) / (max / 2f) : 0f; // -1 (order) .. +1 (chaos)
        int filled = Math.round(Math.abs(balance) * half);
        if (filled > 0) {
            if (balance >= 0) {
                guiGraphics.fill(mid, barY + 1, mid + filled, barY + BAR_HEIGHT - 1, COLOR_CHAOS_BAR);
            } else {
                guiGraphics.fill(mid - filled, barY + 1, mid, barY + BAR_HEIGHT - 1, COLOR_ORDER_BAR);
            }
        }
        guiGraphics.fill(mid, barY, mid + 1, barY + BAR_HEIGHT, 0xFF888888);
    }

    private static Component entropyBalanceText(int entropy, int max) {
        int offset = entropy - max / 2;
        String sign = offset >= 0 ? "+" : "";
        String display = sign + EntropyDisplay.formatBalance(offset);
        return Component.translatable("gui.temporalindustries.entropy.value", display);
    }

    private static Component entropyRateText(float ratePerSecond) {
        String sign = ratePerSecond > 0 ? "+" : "";
        String value = sign + String.format(java.util.Locale.ROOT, "%.1f", ratePerSecond);
        return Component.translatable("gui.temporalindustries.entropy.rate", value);
    }
}
