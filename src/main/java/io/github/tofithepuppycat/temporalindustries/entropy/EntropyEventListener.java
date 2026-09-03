package io.github.tofithepuppycat.temporalindustries.entropy;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

import java.util.Set;

/**
 * Spawns {@link EntropyOrbEntity}s for naturally-occurring order/chaos events, as opposed to the
 * machine-driven spawners in {@code block.entity} (Seebeck generator, Schrodinger Generator). ORDER:
 * obsidian/basalt/cobblestone generation, crop growth, items despawning, passive/neutral mob death,
 * furnace "simplification" smelts (IDEAS.md). CHAOS: player death, splash/lingering potions, hostile
 * mob death.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID)
public final class EntropyEventListener {
    private static final int OBSIDIAN_ORDER = 2;
    private static final int BASALT_ORDER = 1;
    private static final int COBBLESTONE_ORDER = 1;
    private static final int CROP_GROWTH_ORDER = 1;
    private static final int ITEM_DESPAWN_ORDER = 1;

    private static final int PLAYER_DEATH_CHAOS = 3;
    private static final int SPLASH_POTION_CHAOS = 1;
    private static final int LINGERING_POTION_CHAOS = 2;
    private static final int HOSTILE_MOB_DEATH_CHAOS = 1;
    private static final int PASSIVE_MOB_DEATH_ORDER = 1;

    /** Outputs of vanilla furnace recipes that "simplify" a material, per IDEAS.md - identified by
     * the smelted result alone since each is unique to one vanilla smelting recipe. */
    private static final Set<Item> SIMPLIFICATION_RESULTS = Set.of(
            Items.STONE, Items.SMOOTH_STONE, Items.GLASS, Items.BRICK,
            Items.SMOOTH_SANDSTONE, Items.SMOOTH_RED_SANDSTONE, Items.SMOOTH_QUARTZ, Items.DEEPSLATE);
    private static final float SMELT_ORDER_CHANCE = 0.15F;

    private EntropyEventListener() {}

    @SubscribeEvent
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Block result = event.getNewState().getBlock();
        int value;
        if (result == Blocks.OBSIDIAN) value = OBSIDIAN_ORDER;
        else if (result == Blocks.BASALT) value = BASALT_ORDER;
        else if (result == Blocks.COBBLESTONE) value = COBBLESTONE_ORDER;
        else return;

        BlockPos pos = event.getPos();
        EntropyOrbEntity.spawn(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, EntropyType.ORDER, value);
    }

    @SubscribeEvent
    public static void onCropGrow(CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        EntropyOrbEntity.spawn(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, EntropyType.ORDER, CROP_GROWTH_ORDER);
    }

    @SubscribeEvent
    public static void onItemExpire(ItemExpireEvent event) {
        ItemEntity item = event.getEntity();
        if (!(item.level() instanceof ServerLevel level)) return;

        EntropyOrbEntity.spawn(level, item.getX(), item.getY() + 0.2, item.getZ(), EntropyType.ORDER, ITEM_DESPAWN_ORDER);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;

        if (entity instanceof ServerPlayer player) {
            EntropyOrbEntity.spawn(level, player.getX(), player.getY() + 0.5, player.getZ(), EntropyType.CHAOS, PLAYER_DEATH_CHAOS);
            return;
        }

        EntropyType type = entity instanceof Enemy ? EntropyType.CHAOS : EntropyType.ORDER;
        int value = entity instanceof Enemy ? HOSTILE_MOB_DEATH_CHAOS : PASSIVE_MOB_DEATH_ORDER;
        EntropyOrbEntity.spawn(level, entity.getX(), entity.getY() + 0.5, entity.getZ(), type, value);
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion)) return;
        if (!(potion.level() instanceof ServerLevel level)) return;

        int value = potion.getItem().is(Items.LINGERING_POTION) ? LINGERING_POTION_CHAOS : SPLASH_POTION_CHAOS;
        EntropyOrbEntity.spawn(level, potion.getX(), potion.getY(), potion.getZ(), EntropyType.CHAOS, value);
    }

    @SubscribeEvent
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (!SIMPLIFICATION_RESULTS.contains(event.getSmelting().getItem())) return;

        ServerPlayer player = event.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || !(player.level() instanceof ServerLevel level)) return;

        RandomSource random = level.getRandom();
        int rolls = Math.min(event.getSmelting().getCount(), 64);
        int value = 0;
        for (int i = 0; i < rolls; i++) {
            if (random.nextFloat() < SMELT_ORDER_CHANCE) value++;
        }
        EntropyOrbEntity.spawn(level, player.getX(), player.getY() + 0.5, player.getZ(), EntropyType.ORDER, value);
    }
}
