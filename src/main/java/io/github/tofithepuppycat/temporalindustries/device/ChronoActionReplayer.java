package io.github.tofithepuppycat.temporalindustries.device;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Replays a recording's player interactions through an {@link EchoPlayer}, so they behave exactly
 * as if the original player did them: block use (placing, stripping, buttons...),
 * item use (throwing potions, drawing bows, eating), entity use (breeding, milking, leashing),
 * attacks, breaking and riding. Every protection/claim mod sees the usual
 * events too.
 * <p>
 * Items come from the projector's own inventory, never from thin air: before each action the
 * recorded stack is pulled out of it into the fake player's hand, and afterwards everything the
 * fake player ends up holding (including results like a filled bucket) goes back in, with any
 * overflow dropped. The one exception is attack/break tools, which are phantom copies so a loop
 * doesn't wear out or need to stock the original weapon.
 */
public final class ChronoActionReplayer {
    /** How far a replayed action may find its target from where it was recorded: entities wander. */
    private static final double ENTITY_SEARCH_RADIUS = 2.5;
    private static final double VEHICLE_SEARCH_RADIUS = 3.0;

    private final ServerLevel level;
    private final IItemHandler inventory;
    private final EchoPlayer actor;

    /** Entities already claimed by an ATTACK this tick, so two recorded hits can't resolve onto the same mob. */
    private final Set<Integer> attackedThisTick = new HashSet<>();

    public ChronoActionReplayer(ServerLevel level, IItemHandler inventory, BlockPos home, ChronoRecording recording) {
        this.level = level;
        this.inventory = inventory;

        String owner = recording.getOwnerName();
        UUID id = UUID.nameUUIDFromBytes(("temporalindustries:echo:" + level.dimension().location() + ":" + home.asLong())
                .getBytes(StandardCharsets.UTF_8));
        this.actor = new EchoPlayer(level, new GameProfile(id, owner == null || owner.isEmpty() ? "Echo" : owner),
                recording.getOwnerId());
    }

    /** Puts the fake player where the original player was at this point of the loop and advances it one tick. */
    public void beginTick(ChronoRecording recording, int tick) {
        attackedThisTick.clear();

        ChronoRecording.Frame frame = recording.frameAt(tick);
        double x = recording.getStartX() + frame.x();
        double y = recording.getStartY() + frame.y();
        double z = recording.getStartZ() + frame.z();

        // A rider is carried by its vehicle; only its facing still follows the recording.
        if (actor.isPassenger()) {
            actor.setYRot(frame.yaw());
            actor.setXRot(frame.pitch());
        } else {
            actor.moveTo(x, y, z, frame.yaw(), frame.pitch());
        }
        actor.setYHeadRot(frame.yaw());
        actor.setYBodyRot(frame.yaw());
        actor.setShiftKeyDown(frame.crouching());
        actor.setPose(frame.crouching() ? Pose.CROUCHING : Pose.STANDING);
        actor.tickEcho();
    }

    /** Takes back whatever the fake player is holding, unless it's still mid-use (drawing a bow, eating). */
    public void endTick() {
        if (!actor.isUsingItem()) reclaim();
    }

    /** The loop wrapped or playback stopped: drop any ongoing state so the next pass starts clean. */
    public void reset() {
        actor.stopRiding();
        actor.stopUsingItem();
        reclaim();
    }

    public void execute(ChronoRecording.Action action, BlockPos pos) {
        // Everything below touches the world around pos; never force-load a chunk just to replay into it.
        if (!level.hasChunkAt(pos) && action.type() != ChronoRecording.ActionType.DISMOUNT
                && action.type() != ChronoRecording.ActionType.RELEASE_ITEM) {
            return;
        }

        switch (action.type()) {
            case BREAK -> breakBlock(pos, action.tool());
            case ATTACK -> attack(pos, action);
            case USE_BLOCK -> useBlock(pos, action.interaction());
            case USE_ITEM -> useItem(action.interaction());
            case RELEASE_ITEM -> actor.releaseUsingItem();
            case USE_ENTITY -> useEntity(pos, action);
            case MOUNT -> mount(pos, action.targetEntityType());
            case DISMOUNT -> actor.stopRiding();
            default -> { }
        }
    }

    /** Breaks the block with a phantom copy of the recorded tool and collects its drops into the projector. */
    private void breakBlock(BlockPos pos, @Nullable ResourceLocation toolId) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0F) return;

        ItemStack tool = toolId != null
                ? BuiltInRegistries.ITEM.getOptional(toolId).map(ItemStack::new).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        prepare(InteractionHand.MAIN_HAND, tool, false);

        AABB area = new AABB(pos).inflate(1.0);
        Set<ItemEntity> existing = new HashSet<>(level.getEntitiesOfClass(ItemEntity.class, area));
        actor.gameMode.destroyBlock(pos);

        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area)) {
            if (existing.contains(drop)) continue;
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, drop.getItem(), false);
            if (remainder.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(remainder);
            }
        }
        discardHeld();
    }

    private void attack(BlockPos pos, ChronoRecording.Action action) {
        ChronoRecording.Interaction use = action.interaction();
        if (use == null) return;

        Entity target = findEntity(pos, action.targetEntityType(), ENTITY_SEARCH_RADIUS, action.targetBaby(), true);
        // Players are never recorded as targets, but one standing in the swing's way gets hit too
        // (as if it were the target), even if the recorded target is long gone.
        Player bystander = findPlayer(pos);
        if (target == null && bystander == null) return;

        prepare(InteractionHand.MAIN_HAND, parse(use.heldStack()), use.sneaking());
        for (Entity victim : new Entity[] {target, bystander}) {
            if (victim == null) continue;
            attackedThisTick.add(victim.getId());
            actor.setAttackStrength(use.strength());
            actor.attack(victim);
        }
        discardHeld();
    }

    private void useBlock(BlockPos pos, @Nullable ChronoRecording.Interaction use) {
        if (use == null || use.face() == null) return;

        InteractionHand hand = handOf(use);
        ItemStack held = equipRecorded(hand, use);
        if (held == null) return;

        Vec3 hit = Vec3.atLowerCornerOf(pos).add(use.hitX(), use.hitY(), use.hitZ());
        actor.gameMode.useItemOn(actor, level, held, hand, new BlockHitResult(hit, use.face(), pos, use.inside()));
    }

    private void useItem(@Nullable ChronoRecording.Interaction use) {
        if (use == null) return;

        InteractionHand hand = handOf(use);
        ItemStack held = equipRecorded(hand, use);
        if (held == null || held.isEmpty()) return;

        actor.gameMode.useItem(actor, level, held, hand);
    }

    private void useEntity(BlockPos pos, ChronoRecording.Action action) {
        ChronoRecording.Interaction use = action.interaction();
        if (use == null) return;

        Entity target = findEntity(pos, action.targetEntityType(), ENTITY_SEARCH_RADIUS, action.targetBaby(), false);
        if (target == null) return;

        InteractionHand hand = handOf(use);
        if (equipRecorded(hand, use) == null) return;

        // Mirrors what the server does with the client's interact packets: the event first, then the entity.
        if (use.specific()) {
            Vec3 local = new Vec3(use.hitX(), use.hitY(), use.hitZ());
            if (CommonHooks.onInteractEntityAt(actor, target, local, hand) == null) {
                target.interactAt(actor, local, hand);
            }
        } else if (CommonHooks.onInteractEntity(actor, target, hand) == null) {
            actor.interactOn(target, hand);
        }
    }

    private void mount(BlockPos pos, @Nullable ResourceLocation vehicleType) {
        Entity vehicle = findEntity(pos, vehicleType, VEHICLE_SEARCH_RADIUS, null, false);
        if (vehicle == null || vehicle == actor.getVehicle()) return;

        actor.stopRiding();
        actor.startRiding(vehicle);
    }

    /** Empties the fake player's hands back into the projector, then equips the stack the recorded
     * player was holding. Null (nothing equipped) if the projector doesn't have it. */
    @Nullable
    private ItemStack equipRecorded(InteractionHand hand, ChronoRecording.Interaction use) {
        if (actor.isUsingItem()) actor.stopUsingItem();
        reclaim();

        ItemStack held = takeHeld(use);
        if (held == null) return null;
        equip(hand, held, use.sneaking());
        return held;
    }

    /** Empties the fake player's hands back into the projector and equips {@code stack} (a phantom,
     * or something just taken from the projector) in {@code hand}. */
    private void prepare(InteractionHand hand, ItemStack stack, boolean sneaking) {
        if (actor.isUsingItem()) actor.stopUsingItem();
        reclaim();
        equip(hand, stack, sneaking);
    }

    private void equip(InteractionHand hand, ItemStack stack, boolean sneaking) {
        actor.setItemInHand(hand, stack);
        actor.setShiftKeyDown(sneaking);
        actor.refreshEquipment();
    }

    /** Throws away a phantom stack instead of returning it to the projector. */
    private void discardHeld() {
        actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        actor.refreshEquipment();
    }

    /** Moves everything the fake player holds back into the projector, dropping what doesn't fit. */
    private void reclaim() {
        Inventory held = actor.getInventory();
        boolean changed = false;
        for (NonNullList<ItemStack> list : List.of(held.items, held.armor, held.offhand)) {
            for (int slot = 0; slot < list.size(); slot++) {
                ItemStack stack = list.get(slot);
                if (stack.isEmpty()) continue;

                list.set(slot, ItemStack.EMPTY);
                changed = true;
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, stack, false);
                if (!remainder.isEmpty()) {
                    Containers.dropItemStack(level, actor.getX(), actor.getY(), actor.getZ(), remainder);
                }
            }
        }
        if (changed) actor.refreshEquipment();
    }

    /** The recorded held stack, pulled out of the projector's inventory. Empty if the player was
     * empty-handed; null if the projector has no matching stack, in which case the action is skipped. */
    @Nullable
    private ItemStack takeHeld(ChronoRecording.Interaction use) {
        ItemStack wanted = parse(use.heldStack());
        if (wanted.isEmpty()) return ItemStack.EMPTY;

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack have = inventory.getStackInSlot(slot);
            if (have.isEmpty() || !matches(have, wanted)) continue;

            ItemStack taken = inventory.extractItem(slot, have.getCount(), false);
            if (!taken.isEmpty()) return taken;
        }
        return null;
    }

    private ItemStack parse(@Nullable CompoundTag tag) {
        return tag == null ? ItemStack.EMPTY : ItemStack.parseOptional(level.registryAccess(), tag);
    }

    /** Same item and components, ignoring wear: a worn axe stands in for the same axe at recording time. */
    private static boolean matches(ItemStack have, ItemStack wanted) {
        if (!have.is(wanted.getItem())) return false;

        ItemStack a = have.copyWithCount(1);
        ItemStack b = wanted.copyWithCount(1);
        a.remove(DataComponents.DAMAGE);
        b.remove(DataComponents.DAMAGE);
        return ItemStack.isSameItemSameComponents(a, b);
    }

    private static InteractionHand handOf(ChronoRecording.Interaction use) {
        return use.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /** Nearest player around {@code pos} that hasn't already been hit this tick. */
    @Nullable
    private Player findPlayer(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        return level.getEntitiesOfClass(Player.class, new AABB(pos).inflate(ENTITY_SEARCH_RADIUS),
                        p -> p != actor && !p.isSpectator() && !attackedThisTick.contains(p.getId()))
                .stream()
                .min(Comparator.comparingDouble(p -> p.distanceToSqr(center)))
                .orElse(null);
    }

    /** Nearest entity of {@code typeId} around {@code pos}. {@code baby} (if non-null) must match for living entities. */
    @Nullable
    private Entity findEntity(BlockPos pos, @Nullable ResourceLocation typeId, double radius, @Nullable Boolean baby,
                               boolean excludeAttacked) {
        if (typeId == null) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        if (type == null) return null;

        Vec3 center = Vec3.atCenterOf(pos);
        return level.getEntities((Entity) null, new AABB(pos).inflate(radius),
                        e -> e.getType() == type && e != actor && !(e instanceof Player) && !e.isRemoved()
                                && !(excludeAttacked && attackedThisTick.contains(e.getId()))
                                && (baby == null || !(e instanceof LivingEntity living) || living.isBaby() == baby))
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(center)))
                .orElse(null);
    }
}
