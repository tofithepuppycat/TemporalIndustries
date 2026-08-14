package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.data.PlayerTemporalState;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.item.PortableChronoMarkerItem;
import io.github.tofithepuppycat.temporalindustries.network.AnchorStatusPacket;
import io.github.tofithepuppycat.temporalindustries.timeline.BlockChangeDelta;
import io.github.tofithepuppycat.temporalindustries.timeline.EntityDelta;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Central event hub. Replaces both TemporalAnchorEvents and the block-scanning
 * loop that used to live in TimeMachineBlockEntity.tick().
 *
 * Responsibilities:
 *   - Record player-caused block changes into PlayerTemporalState (anchor system)
 *   - Buffer block changes for tracked chunks into TemporalWorldData.pendingBlockDeltas
 *   - Flush pending deltas into TemporalCommits every FLUSH_INTERVAL_TICKS
 *   - Track entity spawns/deaths in tracked chunks
 *   - Handle player death→respawn revert flow
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID)
public final class TemporalChangeListener {
    private static final int FLUSH_INTERVAL_TICKS = 20;
    private static final Set<UUID> PENDING_REVERTS = new HashSet<>();
    // Entities interacted with this tick, snapshotted just before the interaction runs so the
    // effect (dyeing, shearing, taming, renaming, etc.) can be diffed once it's taken effect —
    // there's no generic "entity data changed" event to hook, so this stands in for one.
    private static final List<PendingEntityCheck> PENDING_ENTITY_CHECKS = new ArrayList<>();

    private record PendingEntityCheck(Entity entity, ResourceLocation dimension, ChunkPos chunkPos, CompoundTag before) {}

    private TemporalChangeListener() {}

    // -------------------------------------------------------------------------
    // Block events

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos().immutable();
        ServerPlayer player = event.getPlayer() instanceof ServerPlayer sp ? sp : null;
        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        if (!shouldRecord(level, data, pos, player)) return;

        // saveWithFullMetadata() is a real cost (full BE NBT), so it's only paid once we already
        // know this change is actually going to be recorded somewhere.
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag prevBETag = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
        BlockChangeDelta delta = new BlockChangeDelta(
                pos, event.getState(), Blocks.AIR.defaultBlockState(), prevBETag, null);

        handleBlockChange(level, data, pos, delta, player);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos().immutable();
        ServerPlayer player = event.getEntity() instanceof ServerPlayer sp ? sp : null;
        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        if (!shouldRecord(level, data, pos, player)) return;

        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag newBETag = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
        BlockChangeDelta delta = new BlockChangeDelta(
                pos, event.getBlockSnapshot().getState(), event.getPlacedBlock(), null, newBETag);

        handleBlockChange(level, data, pos, delta, player);
    }

    /** Covers bulk block placement that isn't attributable to a single BlockEvent.EntityPlaceEvent,
     * most notably a sapling growing into a tree (random tick or bonemeal) via a feature/structure
     * placement — NeoForge fires one MultiPlaceEvent with a snapshot per placed block instead of one
     * EntityPlaceEvent per block. */
    @SubscribeEvent
    public static void onBlockMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        ServerPlayer player = event.getEntity() instanceof ServerPlayer sp ? sp : null;
        TemporalWorldData data = TemporalWorldData.get(level.getServer());

        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockPos pos = snapshot.getPos().immutable();
            if (!shouldRecord(level, data, pos, player)) continue;

            BlockEntity be = level.getBlockEntity(pos);
            CompoundTag newBETag = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
            BlockChangeDelta delta = new BlockChangeDelta(
                    pos, snapshot.getState(), level.getBlockState(pos), null, newBETag);

            handleBlockChange(level, data, pos, delta, player);
        }
    }

    /** Whether this change is actually going to be recorded anywhere (tracked chunk, or an armed
     * player's anchor) — checked before paying for full block-entity NBT serialization. */
    private static boolean shouldRecord(ServerLevel level, TemporalWorldData data, BlockPos pos, @Nullable ServerPlayer player) {
        if (data.isTracked(level.dimension().location(), new ChunkPos(pos))) return true;
        if (player == null) return false;
        PlayerTemporalState state = data.getPlayerState(player.getUUID());
        return state != null && state.isArmed();
    }

    private static void handleBlockChange(ServerLevel level, TemporalWorldData data, BlockPos pos,
                                          BlockChangeDelta delta, ServerPlayer sourcePlayer) {
        ResourceLocation dimension = level.dimension().location();

        // 1. Record for the player's anchor if armed
        if (sourcePlayer != null) {
            PlayerTemporalState state = data.getPlayerState(sourcePlayer.getUUID());
            if (state != null && state.isArmed()) {
                state.recordChange(dimension, delta);
                data.setDirty();
            }
        }

        // 2. Buffer for the timeline if this chunk is tracked by a Time Machine
        ChunkPos chunkPos = new ChunkPos(pos);
        if (data.isTracked(dimension, chunkPos)) {
            data.recordTrackedBlockChange(dimension, chunkPos, delta);
        }
    }

    // -------------------------------------------------------------------------
    // Entity events

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (entity instanceof ServerPlayer) return;

        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        if (!data.isTracked(dimension, chunkPos)) return;

        CompoundTag stateTag = new CompoundTag();
        entity.save(stateTag);
        data.recordEntityDelta(dimension, chunkPos,
                new EntityDelta(entity.getUUID(), EntityDelta.Type.SPAWNED, null, stateTag));
    }

    @SubscribeEvent
    public static void onEntityDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity entity = event.getEntity();
        if (entity instanceof ServerPlayer) return;

        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        if (!data.isTracked(dimension, chunkPos)) return;

        CompoundTag stateTag = new CompoundTag();
        entity.save(stateTag);
        data.recordEntityDelta(dimension, chunkPos,
                new EntityDelta(entity.getUUID(), EntityDelta.Type.REMOVED, stateTag, null));
    }

    /** Fires before the interaction runs (dyeing/shearing/taming/feeding/naming/etc.), so this
     * just snapshots "before" state; the actual diff happens at the end of this same tick once
     * the interaction (if not cancelled) has already applied its effect. */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getTarget();
        if (entity instanceof ServerPlayer) return;

        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
        TemporalWorldData data = TemporalWorldData.get(level.getServer());
        if (!data.isTracked(level.dimension().location(), chunkPos)) return;

        CompoundTag before = new CompoundTag();
        entity.save(before);
        PENDING_ENTITY_CHECKS.add(new PendingEntityCheck(entity, level.dimension().location(), chunkPos, before));
    }

    private static void processPendingEntityChecks(MinecraftServer server) {
        if (PENDING_ENTITY_CHECKS.isEmpty()) return;
        TemporalWorldData data = TemporalWorldData.get(server);
        for (PendingEntityCheck check : PENDING_ENTITY_CHECKS) {
            // A death in the same tick is already captured by onEntityDeath.
            if (check.entity().isRemoved()) continue;

            CompoundTag after = new CompoundTag();
            check.entity().save(after);
            if (!after.equals(check.before())) {
                data.recordEntityDelta(check.dimension(), check.chunkPos(), new EntityDelta(
                        check.entity().getUUID(), EntityDelta.Type.MODIFIED, check.before(), after));
            }
        }
        PENDING_ENTITY_CHECKS.clear();
    }

    // -------------------------------------------------------------------------
    // Player death / respawn (anchor revert)

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        PlayerTemporalState state = data.getPlayerState(player.getUUID());
        if (state == null || !state.isArmed()) return;

        PENDING_REVERTS.add(player.getUUID());
        // Clear inventory now so vanilla doesn't drop items at the death location;
        // the checkpoint restore will put everything back on respawn.
        player.getInventory().clearContent();
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PENDING_REVERTS.remove(player.getUUID())) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        TemporalWorldData data = TemporalWorldData.get(server);
        PlayerTemporalState state = data.getPlayerState(player.getUUID());
        if (state == null || !state.isArmed()) return;

        PlayerSnapshot checkpoint = state.getCheckpoint();
        int reverted = state.revertWorldChanges(server);
        state.clearCheckpoint();
        data.setDirty();

        checkpoint.applyTo(player);

        PacketDistributor.sendToPlayer(player,
                new AnchorStatusPacket(
                        reverted,
                        player.getFoodData().getFoodLevel(),
                        player.getFoodData().getSaturationLevel(),
                        player.getFoodData().getExhaustionLevel()));
    }

    // -------------------------------------------------------------------------
    // Flush loop

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        processPendingEntityChecks(server);

        long gameTime = server.overworld().getGameTime();
        if (gameTime % FLUSH_INTERVAL_TICKS != 0) return;

        // inventoryTick() has no "item was deselected/dropped/its holder logged out" hook to
        // release tracking from directly (see PortableChronoMarkerItem), so this periodically
        // sweeps for owners that stopped refreshing instead.
        PortableChronoMarkerItem.releaseStaleTracking(server, gameTime);
        TemporalWorldData.get(server).flushPendingDeltas(gameTime);
    }
}
