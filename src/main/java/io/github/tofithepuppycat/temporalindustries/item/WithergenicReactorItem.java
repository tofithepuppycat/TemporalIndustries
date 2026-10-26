package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.capture.CapturedMob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;

/**
 * Placeable reactor controller that must first be right-clicked on a Wither, which is stored in a
 * {@link CapturedMob} component the way the Schrodinger Generator traps its mob.
 */
@SuppressWarnings("null")
public class WithergenicReactorItem extends BlockItem {
    public WithergenicReactorItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (stack.has(Registration.CAPTURED_MOB.get())) return InteractionResult.PASS;
        if (target.getType() != EntityType.WITHER) return InteractionResult.PASS;

        Level level = player.level();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        // Creative mode hands this method a copy of the held stack, so the component goes on the real one.
        ItemStack held = player.getItemInHand(hand);
        ItemStack capturing = held.is(this) ? held : stack;
        capturing.set(Registration.CAPTURED_MOB.get(), new CapturedMob(typeId, Optional.ofNullable(target.getCustomName())));
        target.discard();

        level.playSound(null, player.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.6F, 1.6F);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CapturedMob captured = stack.get(Registration.CAPTURED_MOB.get());
        if (captured != null) {
            Component name = captured.customName().orElse(captured.entityType().getDescription());
            tooltip.add(Component.translatable("item.temporalindustries.withergenic_reactor.occupied", name).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            TooltipUtil.appendDescription(tooltip, "item.temporalindustries.withergenic_reactor.tooltip");
        }
    }
}
