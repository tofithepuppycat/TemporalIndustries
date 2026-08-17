package io.github.tofithepuppycat.temporalindustries.item;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.capture.CapturedMob;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
 * Placeable {@link BlockItem} that also doubles as an early-game mob trap: right-click a living,
 * non-player entity to capture it into a {@link CapturedMob} data component (bucket-of-mob style),
 * then place the filled item as a
 * {@link io.github.tofithepuppycat.temporalindustries.block.SchrodingersBox} to have it passively
 * generate CHS while occupied.
 */
@SuppressWarnings("null")
public class SchrodingersBoxItem extends BlockItem {
    /** Entity types that can never be captured. Ships empty; datapacks can extend it (e.g. to exclude bosses). */
    public static final TagKey<EntityType<?>> CAPTURE_BLACKLIST = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "schrodingers_box_blacklist"));

    public SchrodingersBoxItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (stack.has(Registration.CAPTURED_MOB.get())) return InteractionResult.PASS;
        if (target instanceof Player) return InteractionResult.PASS;
        if (target.getType().is(CAPTURE_BLACKLIST)) return InteractionResult.PASS;

        Level level = player.level();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        stack.set(Registration.CAPTURED_MOB.get(), new CapturedMob(typeId, Optional.ofNullable(target.getCustomName())));
        target.discard();

        level.playSound(null, player.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 1.0F, 1.4F);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CapturedMob captured = stack.get(Registration.CAPTURED_MOB.get());
        if (captured != null) {
            Component name = captured.customName().orElse(captured.entityType().getDescription());
            tooltip.add(Component.translatable("item.temporalindustries.schrodingers_box.occupied", name).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            tooltip.add(Component.translatable("item.temporalindustries.schrodingers_box.tooltip").withStyle(ChatFormatting.GRAY));
        }
    }
}
