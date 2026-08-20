package io.github.tofithepuppycat.temporalindustries.entropy;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Spawns {@link EntropyOrbEntity}s for naturally-occurring order/chaos events, as opposed to the
 * machine-driven spawners in {@code block.entity} (Seebeck generator, Schrodinger's Box). ORDER:
 * obsidian/basalt/cobblestone generation, fire extinguished by water, items despawning. CHAOS:
 * player death, fire spreading, splash/lingering potions.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID)
public final class EntropyEventListener {
    private static final int OBSIDIAN_ORDER = 2;
    private static final int BASALT_ORDER = 1;
    private static final int COBBLESTONE_ORDER = 1;
    private static final int FIRE_EXTINGUISH_ORDER = 1;
    private static final int ITEM_DESPAWN_ORDER = 1;

    private static final int PLAYER_DEATH_CHAOS = 3;
    private static final int FIRE_SPREAD_CHAOS = 1;
    private static final int SPLASH_POTION_CHAOS = 1;
    private static final int LINGERING_POTION_CHAOS = 2;

    // Positions currently on fire, per level, so onNeighborNotify can tell a freshly-spread fire
    // (reward chaos) apart from an existing fire just re-notifying its neighbors (aging tick), and
    // a fire going out from natural burnout apart from one put out by adjacent water (reward order).
    private static final Map<Level, Set<BlockPos>> TRACKED_FIRE = new WeakHashMap<>();

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
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos().immutable();
        Set<BlockPos> fireHere = TRACKED_FIRE.computeIfAbsent(level, l -> new HashSet<>());
        boolean isFireNow = event.getState().is(BlockTags.FIRE);
        if (!isFireNow && !fireHere.contains(pos)) return;

        if (isFireNow) {
            if (fireHere.add(pos)) {
                EntropyOrbEntity.spawn(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, EntropyType.CHAOS, FIRE_SPREAD_CHAOS);
            }
        } else if (fireHere.remove(pos) && hasAdjacentWater(level, pos)) {
            EntropyOrbEntity.spawn(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, EntropyType.ORDER, FIRE_EXTINGUISH_ORDER);
        }
    }

    private static boolean hasAdjacentWater(Level level, BlockPos pos) {
        if (level.getFluidState(pos).is(FluidTags.WATER)) return true;
        for (Direction dir : Direction.values()) {
            if (level.getFluidState(pos.relative(dir)).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onItemExpire(ItemExpireEvent event) {
        ItemEntity item = event.getEntity();
        if (!(item.level() instanceof ServerLevel level)) return;

        EntropyOrbEntity.spawn(level, item.getX(), item.getY() + 0.2, item.getZ(), EntropyType.ORDER, ITEM_DESPAWN_ORDER);
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        EntropyOrbEntity.spawn(level, player.getX(), player.getY() + 0.5, player.getZ(), EntropyType.CHAOS, PLAYER_DEATH_CHAOS);
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion)) return;
        if (!(potion.level() instanceof ServerLevel level)) return;

        int value = potion.getItem().is(Items.LINGERING_POTION) ? LINGERING_POTION_CHAOS : SPLASH_POTION_CHAOS;
        EntropyOrbEntity.spawn(level, potion.getX(), potion.getY(), potion.getZ(), EntropyType.CHAOS, value);
    }
}
