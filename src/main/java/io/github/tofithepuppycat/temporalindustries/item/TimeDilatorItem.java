package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Handheld tool: right-click a block to speed up its own ticking for a while, making crops grow,
 * furnaces smelt, and this mod's machines process faster.
 */
@SuppressWarnings("null")
public class TimeDilatorItem extends Item {
    /**
     * Extra ticks applied per server tick, on top of the block's normal one -> 8x overall speed.
     */
    public static final int EXTRA_TICKS_PER_SERVER_TICK = 7;
    /** How long one application lasts. */
    public static final int DURATION_TICKS = 3 * 20;

    public TimeDilatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos().immutable();
        TemporalWorldData.get(serverLevel.getServer())
                .accelerateBlock(serverLevel.dimension().location(), pos, DURATION_TICKS);

        ItemStack stack = context.getItemInHand();
        stack.hurtAndBreak(1, serverLevel, player, item -> {});

        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.6F);
        player.displayClientMessage(Component.translatable("item.temporalindustries.time_dilator.accelerated"), true);

        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.temporalindustries.time_dilator.quote")
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
        TooltipUtil.appendDescription(tooltip, "item.temporalindustries.time_dilator.tooltip");
    }
}
