package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.item.EchoRecordItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Watches a recording player's interactions and appends them to whichever Echo Record ({@link
 * EchoRecordItem}) is currently recording, regardless of what's in hand.
 */
public final class ChronoActionRecorder {
    /**
     * Mirrors EchoRecordItem's own cap so an action can never reference a tick beyond what the
     * recording could possibly retain.
     */
    private static final int MAX_FRAMES = 20 * 60;

    // Together these keep a finished recording well inside the item component sync limit.
    private static final int MAX_ACTIONS = 256;
    private static final int MAX_ACTION_BYTES = 4096;

    /** player UUID -> the container-capable block they most recently right-clicked. */
    private static final Map<UUID, BlockPos> PENDING_CONTAINER_POS = new HashMap<>();

    private record OpenSession(BlockPos pos, long tick, Map<Item, Integer> inventorySnapshot) {}

    /** player UUID -> the container GUI session currently open for them, if any. */
    private static final Map<UUID, OpenSession> OPEN_SESSIONS = new HashMap<>();

    /** An interaction seen this tick, waiting for the tick to end before it's written. */
    private record Pending(UUID playerId, long gameTime, BlockPos pos, ChronoRecording.ActionType type,
                            @Nullable ResourceLocation targetType, boolean targetBaby, int entityId,
                            @Nullable CompoundTag use, @Nullable ResourceLocation tool) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    private ChronoActionRecorder() {}

    @Nullable
    private static ServerPlayer recordingPlayer(@Nullable Entity entity) {
        return entity instanceof ServerPlayer player && !(player instanceof FakePlayer) ? player : null;
    }

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        ServerPlayer player = recordingPlayer(event.getPlayer());
        if (player == null) return;

        ItemStack tool = player.getMainHandItem();
        ResourceLocation toolId = tool.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(tool.getItem());

        queue(player, event.getPos(), ChronoRecording.ActionType.BREAK, null, false, -1, null, toolId);
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ServerPlayer player = recordingPlayer(event.getEntity());
        if (player == null || !(event.getLevel() instanceof ServerLevel level)) return;

        if (level.getCapability(Capabilities.ItemHandler.BLOCK, event.getPos(), null) != null) {
            PENDING_CONTAINER_POS.put(player.getUUID(), event.getPos());
        }

        ItemStack held = event.getItemStack();
        if (held.getItem() instanceof EchoRecordItem) return;
        if (event.getUseBlock() == TriState.FALSE && event.getUseItem() == TriState.FALSE) return;

        BlockHitResult hit = event.getHitVec();
        Vec3 rel = hit.getLocation().subtract(Vec3.atLowerCornerOf(event.getPos()));
        ChronoRecording.Interaction use = new ChronoRecording.Interaction(
                event.getHand() == InteractionHand.OFF_HAND, hit.getDirection(),
                (float) rel.x, (float) rel.y, (float) rel.z, hit.isInside(), player.isShiftKeyDown(),
                false, 0F, saveStack(held, player));
        queue(player, event.getPos(), ChronoRecording.ActionType.USE_BLOCK, null, false, -1, use.toTag(), null);
    }

    /**
     * Right-clicking air (or a block that declined) with an item: throwing potions and ender
     * pearls, eating, drawing a bow, casting a rod...
     */
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ServerPlayer player = recordingPlayer(event.getEntity());
        ItemStack held = event.getItemStack();
        if (player == null || held.isEmpty() || held.getItem() instanceof EchoRecordItem) return;

        ChronoRecording.Interaction use = new ChronoRecording.Interaction(
                event.getHand() == InteractionHand.OFF_HAND, null, 0F, 0F, 0F, false, player.isShiftKeyDown(),
                false, 0F, saveStack(held, player));
        queue(player, player.blockPosition(), ChronoRecording.ActionType.USE_ITEM, null, false, -1, use.toTag(), null);
    }

    /** Letting go of an item that was being used over time, e.g. releasing a drawn bow. */
    public static void onUseItemStop(LivingEntityUseItemEvent.Stop event) {
        ServerPlayer player = recordingPlayer(event.getEntity());
        if (player == null) return;

        queue(player, player.blockPosition(), ChronoRecording.ActionType.RELEASE_ITEM, null, false, -1, null, null);
    }

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        queueEntityUse(event.getEntity(), event.getTarget(), event.getHand(), event.getItemStack(), Vec3.ZERO, false);
    }

    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        queueEntityUse(event.getEntity(), event.getTarget(), event.getHand(), event.getItemStack(), event.getLocalPos(), true);
    }

    private static void queueEntityUse(Player who, Entity target, InteractionHand hand, ItemStack held, Vec3 local,
                                        boolean specific) {
        ServerPlayer player = recordingPlayer(who);
        if (player == null || target instanceof Player || held.getItem() instanceof EchoRecordItem) return;

        ChronoRecording.Interaction use = new ChronoRecording.Interaction(
                hand == InteractionHand.OFF_HAND, null, (float) local.x, (float) local.y, (float) local.z, false,
                player.isShiftKeyDown(), specific, 0F, saveStack(held, player));
        queue(player, target.blockPosition(), ChronoRecording.ActionType.USE_ENTITY, entityTypeId(target),
                target instanceof LivingEntity living && living.isBaby(), target.getId(), use.toTag(), null);
    }

    /**
     * A left-click on an entity, with the weapon and its attack cooldown charge so replay can
     * reproduce the damage by really attacking instead of applying a recorded number.
     */
    public static void onAttackEntity(AttackEntityEvent event) {
        ServerPlayer player = recordingPlayer(event.getEntity());
        Entity target = event.getTarget();
        if (player == null || target instanceof Player) return;

        ChronoRecording.Interaction use = new ChronoRecording.Interaction(
                false, null, 0F, 0F, 0F, false, player.isShiftKeyDown(), false,
                player.getAttackStrengthScale(0.5F), saveStack(player.getMainHandItem(), player));
        queue(player, target.blockPosition(), ChronoRecording.ActionType.ATTACK, entityTypeId(target),
                target instanceof LivingEntity living && living.isBaby(), target.getId(), use.toTag(), null);
    }

    /** Mounting a boat, minecart, horse, seat... however it happened. */
    public static void onEntityMount(EntityMountEvent event) {
        ServerPlayer player = recordingPlayer(event.getEntityMounting());
        if (player == null || player.level().isClientSide) return;

        if (event.isDismounting()) {
            queue(player, player.blockPosition(), ChronoRecording.ActionType.DISMOUNT, null, false, -1, null, null);
            return;
        }

        Entity vehicle = event.getEntityBeingMounted();
        PENDING.removeIf(p -> p.playerId().equals(player.getUUID())
                && p.type() == ChronoRecording.ActionType.USE_ENTITY && p.entityId() == vehicle.getId());
        queue(player, vehicle.blockPosition(), ChronoRecording.ActionType.MOUNT, entityTypeId(vehicle), false,
                vehicle.getId(), null, null);
    }

    /** Once per server tick, writes out everything queued during it. */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;

        MinecraftServer server = event.getServer();
        for (Pending p : PENDING) {
            ServerPlayer player = server.getPlayerList().getPlayer(p.playerId());
            if (player != null) writePending(player, p);
        }
        PENDING.clear();
    }

    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        ServerPlayer player = recordingPlayer(event.getEntity());
        if (player == null) return;

        // Whatever right-click opened the GUI is represented by the item transfers instead; the
        // fake player can't operate a menu, so replaying the click would only waste energy.
        PENDING.removeIf(p -> p.playerId().equals(player.getUUID()) && (p.type() == ChronoRecording.ActionType.USE_BLOCK
                || p.type() == ChronoRecording.ActionType.USE_ENTITY || p.type() == ChronoRecording.ActionType.USE_ITEM));

        BlockPos pos = PENDING_CONTAINER_POS.remove(player.getUUID());
        if (pos == null || findRecordingRecorder(player) == null) return;

        long tick = player.level().getGameTime();
        OPEN_SESSIONS.put(player.getUUID(), new OpenSession(pos, tick, snapshotInventory(player)));
    }

    public static void onContainerClose(PlayerContainerEvent.Close event) {
        ServerPlayer player = recordingPlayer(event.getEntity());
        if (player == null) return;

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
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (delta < 0) {
                recordAction(player, session.pos(), ChronoRecording.ActionType.INSERT, session.tick(), action -> {
                    action.putString("Item", itemId.toString());
                    action.putInt("Count", -delta);
                });
            } else if (delta > 0) {
                recordAction(player, session.pos(), ChronoRecording.ActionType.EXTRACT, session.tick(), action -> {
                    action.putString("Item", itemId.toString());
                    action.putInt("Count", delta);
                });
            }
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING_CONTAINER_POS.remove(id);
        OPEN_SESSIONS.remove(id);
        PENDING.removeIf(p -> p.playerId().equals(id));
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

    @Nullable
    private static CompoundTag saveStack(ItemStack stack, ServerPlayer player) {
        return stack.isEmpty() ? null : (CompoundTag) stack.save(player.registryAccess());
    }

    @Nullable
    private static ResourceLocation entityTypeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
    }

    /** Queues an action for the end of this tick, skipping players who aren't recording. */
    private static void queue(ServerPlayer player, BlockPos pos, ChronoRecording.ActionType type,
                               @Nullable ResourceLocation targetType, boolean targetBaby, int entityId,
                               @Nullable CompoundTag use, @Nullable ResourceLocation tool) {
        if (findRecordingRecorder(player) == null) return;
        PENDING.add(new Pending(player.getUUID(), player.level().getGameTime(), pos, type, targetType, targetBaby,
                entityId, use, tool));
    }

    private static void writePending(ServerPlayer player, Pending p) {
        recordAction(player, p.pos(), p.type(), p.gameTime(), action -> {
            if (p.targetType() != null) {
                action.putString("TargetType", p.targetType().toString());
                action.putBoolean("TargetBaby", p.targetBaby());
            }
            if (p.use() != null) action.put("Use", p.use());
            if (p.tool() != null) action.putString("Tool", p.tool().toString());
        });
    }

    /**
     * Appends one action to the player's recording, with {@code fill} adding its type-specific
     * fields.
     */
    private static void recordAction(ServerPlayer player, BlockPos pos, ChronoRecording.ActionType type,
                                      long gameTime, Consumer<CompoundTag> fill) {
        ItemStack recorder = findRecordingRecorder(player);
        if (recorder == null) return;

        CompoundTag data = EchoRecordItem.readData(recorder);
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
        fill.accept(action);
        if (action.sizeInBytes() > MAX_ACTION_BYTES) return;

        ListTag actions = data.getList("Actions", Tag.TAG_COMPOUND);
        if (actions.size() >= MAX_ACTIONS) return;
        actions.add(action);
        data.put("Actions", actions);
        EchoRecordItem.writeData(recorder, data);
    }

    @Nullable
    private static ItemStack findRecordingRecorder(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (EchoRecordItem.isRecording(stack)) return stack;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (EchoRecordItem.isRecording(stack)) return stack;
        }
        return null;
    }
}
