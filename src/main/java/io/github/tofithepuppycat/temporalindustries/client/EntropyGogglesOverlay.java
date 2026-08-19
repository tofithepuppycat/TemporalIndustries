package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
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
        if (lines.isEmpty()) return;

        Font font = minecraft.font;
        int y = guiGraphics.guiHeight() / 2 - 60;
        for (Component line : lines) {
            int width = font.width(line);
            int x = (guiGraphics.guiWidth() - width) / 2;
            guiGraphics.drawStringWithBackdrop(font, line, x, y, width, 0xFFFFFF);
            y += font.lineHeight + 2;
        }
    }
}
