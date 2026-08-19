package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/** Draws the block the player is looking at's entropy info above the hotbar, while Entropy Goggles
 * are worn in the helmet slot. See EntropyInfoProvider for which block entities report info. */
public final class EntropyGogglesOverlay implements LayeredDraw.Layer {
    public static final EntropyGogglesOverlay INSTANCE = new EntropyGogglesOverlay();

    /** Mirrors the bidirectional balance bar drawn by ChronosphereScreen/ChronovaultScreen. */
    private static final int BAR_WIDTH = 60;
    private static final int BAR_HEIGHT = 8;
    private static final int COLOR_ORDER_BAR = 0xFF000000 | EntropyType.ORDER.color();
    private static final int COLOR_CHAOS_BAR = 0xFF000000 | EntropyType.CHAOS.color();

    private EntropyGogglesOverlay() {}

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) return;

        Player player = minecraft.player;
        if (player == null || !player.getItemBySlot(EquipmentSlot.HEAD).is(Registration.ENTROPY_GOGGLES_ITEM.get())) return;

        Level level = minecraft.level;
        if (level == null || !(minecraft.hitResult instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) return;

        BlockEntity blockEntity = level.getBlockEntity(blockHit.getBlockPos());
        if (!(blockEntity instanceof EntropyInfoProvider provider)) return;

        List<Component> lines = provider.getEntropyTooltip();
        if (lines.isEmpty() && !provider.hasEntropyBalance()) return;

        Font font = minecraft.font;
        int y = guiGraphics.guiHeight() / 2 - 60;
        for (Component line : lines) {
            y = drawCenteredLine(guiGraphics, font, line, y);
        }

        if (provider.hasEntropyBalance()) {
            int entropy = provider.getEntropyBalance();
            int max = provider.getEntropyBalanceMax();

            int barX = (guiGraphics.guiWidth() - BAR_WIDTH) / 2;
            renderEntropyBar(guiGraphics, barX, y, entropy, max);
            y += BAR_HEIGHT + 3;

            drawCenteredLine(guiGraphics, font, entropyBalanceText(entropy, max), y);
        }
    }

    private static int drawCenteredLine(GuiGraphics guiGraphics, Font font, Component line, int y) {
        int width = font.width(line);
        int x = (guiGraphics.guiWidth() - width) / 2;
        guiGraphics.drawStringWithBackdrop(font, line, x, y, width, 0xFFFFFF);
        return y + font.lineHeight + 2;
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
        int half = max / 2;
        if (entropy == half) return Component.translatable("gui.temporalindustries.entropy.balanced", entropy, max);
        String key = entropy > half ? "gui.temporalindustries.entropy.chaos" : "gui.temporalindustries.entropy.order";
        return Component.translatable(key, entropy, max);
    }
}
