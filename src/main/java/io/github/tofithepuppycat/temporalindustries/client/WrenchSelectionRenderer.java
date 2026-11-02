package io.github.tofithepuppycat.temporalindustries.client;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.WrenchConfigurable;
import io.github.tofithepuppycat.temporalindustries.item.WrenchItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * While a {@link WrenchItem} is held with a block selected, draws that block's input and output
 * links the same way the block's own item draws them: red for inputs, blue for outputs.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public class WrenchSelectionRenderer {
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }

        ItemStack wrench = holdingWrench(player);
        if (wrench == null) {
            return;
        }

        BlockPos selected = WrenchItem.getSelected(wrench, level);
        if (selected == null || !(level.getBlockEntity(selected) instanceof WrenchConfigurable configurable)) {
            return;
        }

        EntropicPylonMarkRenderer.renderMarks(event, minecraft, configurable.wrenchInputs(), configurable.wrenchOutputs());
    }

    private static ItemStack holdingWrench(Player player) {
        if (player.getMainHandItem().getItem() instanceof WrenchItem) return player.getMainHandItem();
        if (player.getOffhandItem().getItem() instanceof WrenchItem) return player.getOffhandItem();
        return null;
    }
}
