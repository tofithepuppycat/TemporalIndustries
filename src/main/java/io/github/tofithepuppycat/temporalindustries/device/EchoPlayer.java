package io.github.tofithepuppycat.temporalindustries.device;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * The server-side stand-in an Echo Projector uses to replay a recording's interactions the way
 * the original player performed them. It's a {@link FakePlayer} (never added to the world, so
 * mobs ignore it and it isn't saved) with two differences: it can ride vehicles, and it's driven
 * by {@link #tickEcho()} instead of NeoForge's no-op {@code tick()}, which would otherwise also
 * make it vacuum up nearby item entities.
 */
public class EchoPlayer extends FakePlayer {
    /** {@code FakePlayer.startRiding} unconditionally refuses; this reaches the vanilla
     * {@code Entity} implementation directly, skipping ServerPlayer's and FakePlayer's overrides.
     * Needs a lookup inside Entity itself: an invokespecial from here would resolve against
     * FakePlayer, the direct superclass. */
    private static final MethodHandle ENTITY_START_RIDING;

    static {
        try {
            ENTITY_START_RIDING = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup())
                    .findSpecial(Entity.class, "startRiding",
                            MethodType.methodType(boolean.class, Entity.class, boolean.class), Entity.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Snapshot of the main-hand stack whose attribute modifiers are currently applied. */
    private ItemStack appliedMainHand = ItemStack.EMPTY;

    /** The player whose recording this echo is replaying, if known. */
    @Nullable private final UUID recordingOwner;

    public EchoPlayer(ServerLevel level, GameProfile profile, @Nullable UUID recordingOwner) {
        super(level, profile);
        this.recordingOwner = recordingOwner;
    }

    @Nullable
    public UUID getRecordingOwner() {
        return recordingOwner;
    }

    @Override
    public boolean startRiding(Entity vehicle, boolean force) {
        try {
            boolean mounted = (boolean) ENTITY_START_RIDING.invoke(this, vehicle, force);
            if (mounted) vehicle.positionRider(this);
            return mounted;
        } catch (Throwable t) {
            throw new IllegalStateException("Echo player failed to mount " + vehicle, t);
        }
    }

    /** Just the parts of a player tick an interaction actually depends on: item cooldowns, the
     * attack cooldown charge, and progress of an in-use item (eating, drawing a bow). */
    public void tickEcho() {
        tickCount++;
        fallDistance = 0F;
        getCooldowns().tick();
        if (attackStrengthTicker < 1000) attackStrengthTicker++;

        if (isUsingItem()) {
            ItemStack inHand = getItemInHand(getUsedItemHand());
            if (ItemStack.isSameItem(inHand, getUseItem())) {
                updateUsingItem(inHand);
            } else {
                stopUsingItem();
            }
        }
    }

    /** Swaps the main-hand item's attribute modifiers (attack damage, attack speed...) in for the
     * previous one's. Vanilla does this in the entity tick, which this player doesn't run. */
    public void refreshEquipment() {
        ItemStack now = getMainHandItem();
        if (ItemStack.matches(now, appliedMainHand)) return;

        AttributeMap attributes = getAttributes();
        appliedMainHand.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            AttributeInstance instance = attributes.getInstance(attribute);
            if (instance != null) instance.removeModifier(modifier.id());
        });
        now.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            AttributeInstance instance = attributes.getInstance(attribute);
            if (instance != null) {
                instance.removeModifier(modifier.id());
                instance.addTransientModifier(modifier);
            }
        });
        appliedMainHand = now.copy();
    }

    /** Sets the attack cooldown so the next hit lands with this fraction of full strength. Call
     * after the weapon is in hand, since the cooldown length depends on it. */
    public void setAttackStrength(float strength) {
        int delay = (int) Math.ceil(getCurrentItemAttackStrengthDelay());
        attackStrengthTicker = Math.max(0, (int) Math.ceil(strength * delay - 0.5F));
    }
}
