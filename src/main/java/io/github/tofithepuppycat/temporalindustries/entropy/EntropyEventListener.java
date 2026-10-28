package io.github.tofithepuppycat.temporalindustries.entropy;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.device.TemporalChangeListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

import java.util.Set;

/**
 * Spawns {@link EntropyOrbEntity}s for naturally-occurring order/chaos events (as opposed to the
 * machine-driven spawners in {@code block.entity}): obsidian/cobblestone generation, crop growth,
 * item despawn, harvested compost, finished brews, and "simplification" smelts produce ORDER;
 * player death, thrown potions, hostile mob death, lightning, explosions, and decaying leaves
 * produce CHAOS.
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
    private static final int LIGHTNING_CHAOS = 4;
    private static final int LEAF_DECAY_CHAOS = 1;
    private static final float LEAF_DECAY_CHAOS_CHANCE = 0.25F;
    private static final int BLOCKS_PER_EXPLOSION_CHAOS = 10;
    private static final int MAX_EXPLOSION_CHAOS = 8;
    private static final int COMPOST_HARVEST_ORDER = 2;
    private static final int POTION_BREWED_ORDER = 1;
    private static final int PASSIVE_MOB_DEATH_ORDER = 1;

    /**
     * Outputs of vanilla furnace recipes that "simplify" a material, identified by the smelted
     * result alone since each is unique to one vanilla smelting recipe.
     */
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
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
            if (TemporalChangeListener.anchorWillInterceptDeath(player)) return;
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

    @SubscribeEvent
    public static void onLightningSpawn(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LightningBolt bolt) || event.loadedFromDisk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        EntropyOrbEntity.spawn(level, bolt.getX(), bolt.getY() + 0.5, bolt.getZ(), EntropyType.CHAOS, LIGHTNING_CHAOS);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        int value = Math.min(event.getExplosion().getToBlow().size() / BLOCKS_PER_EXPLOSION_CHAOS, MAX_EXPLOSION_CHAOS);
        Vec3 center = event.getExplosion().center();
        EntropyOrbEntity.spawn(level, center.x, center.y, center.z, EntropyType.CHAOS, value);
    }

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        if (event.getBreaker() != null) return;
        BlockState state = event.getState();
        if (!state.is(BlockTags.LEAVES)) return;
        ServerLevel level = event.getLevel();
        if (level.random.nextFloat() >= LEAF_DECAY_CHAOS_CHANCE) return;

        BlockPos pos = event.getPos();
        EntropyOrbEntity.spawn(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, EntropyType.CHAOS, LEAF_DECAY_CHAOS);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCompostHarvest(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (event.getUseBlock() == TriState.FALSE) return;
        // Vanilla skips the block interaction when a sneaking player holds anything in either hand,
        // unless the use is explicitly forced.
        Player player = event.getEntity();
        boolean holdingItem = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        if (event.getUseBlock() != TriState.TRUE && player.isSecondaryUseActive() && holdingItem) return;

        BlockState state = level.getBlockState(event.getPos());
        if (!state.is(Blocks.COMPOSTER) || state.getValue(ComposterBlock.LEVEL) != ComposterBlock.READY) return;

        BlockPos pos = event.getPos();
        EntropyOrbEntity.spawn(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, EntropyType.ORDER, COMPOST_HARVEST_ORDER);
    }

    /** Called by the brewing stand mixin once a brew cycle has converted the potions. */
    public static void onBrewFinished(Level level, BlockPos pos, NonNullList<ItemStack> items) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        int brewed = 0;
        for (int slot = 0; slot < 3; slot++) {
            if (!items.get(slot).isEmpty()) brewed++;
        }
        EntropyOrbEntity.spawn(serverLevel, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5,
                EntropyType.ORDER, brewed * POTION_BREWED_ORDER);
    }
}
