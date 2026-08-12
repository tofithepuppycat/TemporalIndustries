package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.item.ChronoRecorderItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Watches for a recording player's own block breaks/places and container item transfers, and
 * appends them to whichever of their Chrono Recorders (see {@link ChronoRecorderItem}) is
 * currently recording — regardless of which item is actually in hand, since
 * {@link ChronoRecorderItem#inventoryTick} keeps sampling movement even while a pickaxe/hoe/etc.
 * is wielded.
 *
 * PLACE is captured via {@link BlockEvent.EntityPlaceEvent} rather than the right-click itself,
 * because only after placement do we know the block's actual resulting state (orientation,
 * connections, ...) and any block entity data it ended up with — the right-click is only used to
 * remember which item caused the placement (EntityPlaceEvent doesn't say).
 *
 * Container transfers (e.g. feeding a furnace, taking its output) aren't exposed as a per-slot
 * event by vanilla/NeoForge, so they're inferred: whichever container-capable block the player
 * last right-clicked is remembered, then the player's own inventory is snapshotted on
 * {@link PlayerContainerEvent.Open} and diffed against itself on {@link PlayerContainerEvent.Close}.
 * Items that vanished from the player's inventory during that GUI session were handed to the
 * container (INSERT); items that appeared were taken from it (EXTRACT).
 *
 * Ticks are keyed against the recording's own StartGameTime rather than its frame list size, so
 * ordering relative to inventoryTick within the same server tick doesn't matter.
 */
public final class ChronoActionRecorder {
    /** Mirrors ChronoRecorderItem's own cap so an action can never reference a tick beyond what
     * the recording could possibly retain. */
    private static final int MAX_FRAMES = 20 * 60;

    /** player UUID -> the container-capable block they most recently right-clicked, consumed by
     * the very next container-open on that player (see class doc). */
    private static final Map<UUID, BlockPos> PENDING_CONTAINER_POS = new HashMap<>();

    /** player UUID -> the BlockItem stack they most recently right-clicked with, consumed by the
     * very next EntityPlaceEvent for that player (see class doc). */
    private static final Map<UUID, ItemStack> PENDING_PLACE_ITEM = new HashMap<>();

    private record OpenSession(BlockPos pos, long tick, Map<Item, Integer> inventorySnapshot) {}

    /** player UUID -> the container GUI session currently open for them, if any. */
    private static final Map<UUID, OpenSession> OPEN_SESSIONS = new HashMap<>();

    private record ModifyCandidate(ServerLevel level, BlockPos pos, BlockState beforeState, long gameTime,
                                    @Nullable ResourceLocation item) {}

    /** player UUID -> the block state right before their most recent right-click, checked for a
     * change at the end of the same server tick (see class doc / onServerTick). */
    private static final Map<UUID, ModifyCandidate> PENDING_MODIFY = new HashMap<>();

    private ChronoActionRecorder() {}

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        ItemStack tool = player.getMainHandItem();
        ResourceLocation toolId = tool.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(tool.getItem());

        recordAction(player, event.getPos(), ChronoRecording.ActionType.BREAK, null, 1,
                player.level().getGameTime(), null, null, toolId);
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack held = event.getItemStack();
        if (held.getItem() instanceof BlockItem) {
            PENDING_PLACE_ITEM.put(player.getUUID(), held.copy());
        }

        if (level.getCapability(Capabilities.ItemHandler.BLOCK, event.getPos(), null) != null) {
            PENDING_CONTAINER_POS.put(player.getUUID(), event.getPos());
        }

        ResourceLocation heldId = held.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(held.getItem());
        PENDING_MODIFY.put(player.getUUID(), new ModifyCandidate(
                level, event.getPos(), level.getBlockState(event.getPos()), player.level().getGameTime(), heldId));
    }

    /**
     * Once per server tick, checks every block a recording player right-clicked that tick for
     * whether its state actually changed — catching axe-stripping, hoe-tilling, honeycomb-waxing,
     * and arbitrary modded right-click transforms (e.g. applying a Create casing to a shaft)
     * generically, without needing a dedicated event for each one. A no-op right-click (nothing
     * there worth recording) just falls out of the map unrecorded.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING_MODIFY.isEmpty()) return;

        Iterator<Map.Entry<UUID, ModifyCandidate>> it = PENDING_MODIFY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ModifyCandidate> entry = it.next();
            ModifyCandidate candidate = entry.getValue();
            it.remove();

            BlockState after = candidate.level().getBlockState(candidate.pos());
            if (after.equals(candidate.beforeState())) continue;

            ServerPlayer player = candidate.level().getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;

            CompoundTag blockStateTag = NbtUtils.writeBlockState(after);
            CompoundTag blockEntityTag = null;
            BlockEntity be = candidate.level().getBlockEntity(candidate.pos());
            if (be != null) {
                blockEntityTag = be.saveWithoutMetadata(candidate.level().registryAccess());
            }

            recordAction(player, candidate.pos(), ChronoRecording.ActionType.MODIFY, candidate.item(), 1,
                    candidate.gameTime(), blockStateTag, blockEntityTag, null);
        }
    }

    /** Fires once the block a right-click BlockItem caused has actually been placed — this is where
     * we learn its real resulting BlockState and any block entity data, see class doc. */
    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ItemStack placedWith = PENDING_PLACE_ITEM.remove(player.getUUID());
        if (placedWith == null || placedWith.isEmpty()) return;

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(placedWith.getItem());
        CompoundTag blockStateTag = NbtUtils.writeBlockState(event.getPlacedBlock());

        CompoundTag blockEntityTag = null;
        BlockEntity be = level.getBlockEntity(event.getPos());
        if (be != null) {
            blockEntityTag = be.saveWithoutMetadata(level.registryAccess());
        }

        recordAction(player, event.getPos(), ChronoRecording.ActionType.PLACE, itemId, 1,
                player.level().getGameTime(), blockStateTag, blockEntityTag, null);
    }

    /**
     * Records the player melee-damaging a living entity, keyed off the final post-reduction damage
     * (armor, enchantments, criticals all already applied) rather than the raw hit, so replay can
     * just reapply that same number instead of recreating the whole combat calculation — see
     * {@link ChronoRecording.ActionType#ATTACK}.
     * <p>
     * Only direct melee hits count ({@code getDirectEntity() == player}); projectiles and other
     * indirect damage aren't attributed to an "attack" here. Players are excluded as targets so a
     * replaying loop can never be used to automate PvP against someone who wasn't there when it was
     * recorded.
     * <p>
     * The held item is whatever's in the main hand, vanilla or modded, with no allow-list — any
     * melee weapon or tool that deals damage records the same way. A Sweeping Edge (or modded
     * equivalent) swing that damages several entities fires this event once per entity hit, each
     * producing its own ATTACK action at the same tick with that entity's own recorded position —
     * see {@code ChronoProjectorBlockEntity#executeActions} for how replay keeps those from
     * colliding onto a single target.
     */
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getSource().getDirectEntity() instanceof ServerPlayer player)) return;
        LivingEntity target = event.getEntity();
        if (target instanceof Player || event.getNewDamage() <= 0F) return;

        ItemStack weapon = player.getMainHandItem();
        ResourceLocation weaponId = weapon.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(weapon.getItem());
        ResourceLocation targetType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());

        recordAction(player, target.blockPosition(), ChronoRecording.ActionType.ATTACK, null, 1,
                player.level().getGameTime(), null, null, weaponId, targetType, target.isBaby(), event.getNewDamage());
    }

    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos pos = PENDING_CONTAINER_POS.remove(player.getUUID());
        if (pos == null || findRecordingRecorder(player) == null) return;

        long tick = player.level().getGameTime();
        OPEN_SESSIONS.put(player.getUUID(), new OpenSession(pos, tick, snapshotInventory(player)));
    }

    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        OpenSession session = OPEN_SESSIONS.remove(player.getUUID());
        if (session == null) return;

        ItemStack recorder = findRecordingRecorder(player);
        if (recorder == null) return;

        Map<Item, Integer> before = session.inventorySnapshot();
        Map<Item, Integer> after = snapshotInventory(player);

        Map<Item, Integer> allItems = new HashMap<>(before);
        allItems.putAll(after);

        for (Item item : allItems.keySet()) {
            int delta = after.getOrDefault(item, 0) - before.getOrDefault(item, 0);
            if (delta < 0) {
                recordAction(player, session.pos(), ChronoRecording.ActionType.INSERT,
                        BuiltInRegistries.ITEM.getKey(item), -delta, session.tick(), null, null, null);
            } else if (delta > 0) {
                recordAction(player, session.pos(), ChronoRecording.ActionType.EXTRACT,
                        BuiltInRegistries.ITEM.getKey(item), delta, session.tick(), null, null, null);
            }
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING_CONTAINER_POS.remove(id);
        PENDING_PLACE_ITEM.remove(id);
        PENDING_MODIFY.remove(id);
        OPEN_SESSIONS.remove(id);
    }

    private static Map<Item, Integer> snapshotInventory(ServerPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static void recordAction(ServerPlayer player, BlockPos pos, ChronoRecording.ActionType type,
                                      @Nullable ResourceLocation item, int count, long gameTime,
                                      @Nullable CompoundTag blockState, @Nullable CompoundTag blockEntity,
                                      @Nullable ResourceLocation tool) {
        recordAction(player, pos, type, item, count, gameTime, blockState, blockEntity, tool, null, false, 0F);
    }

    private static void recordAction(ServerPlayer player, BlockPos pos, ChronoRecording.ActionType type,
                                      @Nullable ResourceLocation item, int count, long gameTime,
                                      @Nullable CompoundTag blockState, @Nullable CompoundTag blockEntity,
                                      @Nullable ResourceLocation tool, @Nullable ResourceLocation targetEntityType,
                                      boolean targetBaby, float damage) {
        ItemStack recorder = findRecordingRecorder(player);
        if (recorder == null) return;

        CompoundTag data = ChronoRecorderItem.readData(recorder);
        long startGameTime = data.getLong("StartGameTime");
        long tick = gameTime - startGameTime;
        if (tick < 0 || tick >= MAX_FRAMES) return;

        double startX = data.getDouble("StartX");
        double startY = data.getDouble("StartY");
        double startZ = data.getDouble("StartZ");

        CompoundTag action = new CompoundTag();
        action.putInt("Tick", (int) tick);
        action.putInt("DX", pos.getX() - (int) Math.floor(startX));
        action.putInt("DY", pos.getY() - (int) Math.floor(startY));
        action.putInt("DZ", pos.getZ() - (int) Math.floor(startZ));
        action.putInt("Type", type.ordinal());
        action.putInt("Count", count);
        if (item != null) action.putString("Item", item.toString());
        if (blockState != null) action.put("BlockState", blockState);
        if (blockEntity != null) action.put("BlockEntity", blockEntity);
        if (tool != null) action.putString("Tool", tool.toString());
        if (targetEntityType != null) {
            action.putString("TargetType", targetEntityType.toString());
            action.putBoolean("TargetBaby", targetBaby);
        }
        if (damage != 0F) action.putFloat("Damage", damage);

        ListTag actions = data.getList("Actions", Tag.TAG_COMPOUND);
        actions.add(action);
        data.put("Actions", actions);
        ChronoRecorderItem.writeData(recorder, data);
    }

    @Nullable
    private static ItemStack findRecordingRecorder(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (ChronoRecorderItem.isRecording(stack)) return stack;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (ChronoRecorderItem.isRecording(stack)) return stack;
        }
        return null;
    }
}
