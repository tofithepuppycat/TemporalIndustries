package io.github.tofithepuppycat.temporalindustries.entropy;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.item.EntropyContainerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * ORD/CHS orb, modeled on vanilla {@link net.minecraft.world.entity.ExperienceOrb}: it floats, bobs,
 * merges with nearby orbs of the same {@link EntropyType} and gets picked up on touch. Unlike an xp
 * orb it is only attracted towards (and only picked up by) a player currently holding an
 * {@link EntropyContainerItem} in either hand — see {@link #isHoldingContainer}.
 */
public class EntropyOrbEntity extends Entity {
    private static final int LIFETIME = 6000;
    private static final int MAX_FOLLOW_DIST = 8;
    private static final double ORB_MERGE_DISTANCE = 0.5;

    private static final EntityDataAccessor<Boolean> DATA_CHAOS =
            SynchedEntityData.defineId(EntropyOrbEntity.class, EntityDataSerializers.BOOLEAN);

    private int age;
    private int value = 1;
    private Player followingPlayer;

    public EntropyOrbEntity(EntityType<? extends EntropyOrbEntity> type, Level level) {
        super(type, level);
    }

    public EntropyOrbEntity(Level level, double x, double y, double z, EntropyType type, int value) {
        this(Registration.ENTROPY_ORB.get(), level);
        this.setPos(x, y, z);
        this.setYRot((float) (this.random.nextDouble() * 360.0));
        this.setDeltaMovement((this.random.nextDouble() * 0.2F - 0.1F) * 2.0, this.random.nextDouble() * 0.2 * 2.0,
                (this.random.nextDouble() * 0.2F - 0.1F) * 2.0);
        this.value = value;
        this.setType(type);
    }

    public static void spawn(ServerLevel level, double x, double y, double z, EntropyType type, int value) {
        if (value <= 0) return;
        level.addFreshEntity(new EntropyOrbEntity(level, x, y, z, type, value));
    }

    public EntropyType getEntropyType() {
        return this.entityData.get(DATA_CHAOS) ? EntropyType.CHAOS : EntropyType.ORDER;
    }

    private void setType(EntropyType type) {
        this.entityData.set(DATA_CHAOS, type == EntropyType.CHAOS);
    }

    public int getValue() {
        return this.value;
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_CHAOS, false);
    }

    @Override
    protected double getDefaultGravity() {
        return 0.03;
    }

    @Override
    public void tick() {
        super.tick();
        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        if (this.isEyeInFluid(FluidTags.WATER)) {
            setUnderwaterMovement();
        } else {
            this.applyGravity();
        }

        if (this.level().getFluidState(this.blockPosition()).is(FluidTags.LAVA)) {
            this.setDeltaMovement((this.random.nextFloat() - this.random.nextFloat()) * 0.2F, 0.2F,
                    (this.random.nextFloat() - this.random.nextFloat()) * 0.2F);
        }

        if (!this.level().noCollision(this.getBoundingBox())) {
            this.moveTowardsClosestSpace(this.getX(), (this.getBoundingBox().minY + this.getBoundingBox().maxY) / 2.0, this.getZ());
        }

        if (this.tickCount % 20 == 1) {
            scanForEntities();
        }

        if (this.followingPlayer != null && (this.followingPlayer.isSpectator() || this.followingPlayer.isDeadOrDying())) {
            this.followingPlayer = null;
        }

        if (this.followingPlayer != null) {
            double dx = this.followingPlayer.getX() - this.getX();
            double dy = this.followingPlayer.getY() + this.followingPlayer.getEyeHeight() / 2.0 - this.getY();
            double dz = this.followingPlayer.getZ() - this.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < 64.0) {
                double pull = 1.0 - Math.sqrt(distSq) / 8.0;
                this.setDeltaMovement(this.getDeltaMovement().add(
                        new Vec3(dx, dy, dz).normalize().scale(pull * pull * 0.1)));
            }
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
        float friction = 0.98F;
        if (this.onGround()) {
            BlockPos pos = getBlockPosBelowThatAffectsMyMovement();
            friction = this.level().getBlockState(pos).getFriction(this.level(), pos, this) * 0.98F;
        }

        this.setDeltaMovement(this.getDeltaMovement().multiply(friction, 0.98, friction));
        if (this.onGround()) {
            this.setDeltaMovement(this.getDeltaMovement().multiply(1.0, -0.9, 1.0));
        }

        this.age++;
        if (this.age >= LIFETIME) {
            this.discard();
        }
    }

    @Override
    public BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return this.getOnPos(0.999999F);
    }

    private void scanForEntities() {
        if (this.followingPlayer == null || this.followingPlayer.distanceToSqr(this) > (double) (MAX_FOLLOW_DIST * MAX_FOLLOW_DIST)) {
            Predicate<Entity> predicate = entity -> entity instanceof Player player && !player.isSpectator() && isHoldingContainer(player);
            this.followingPlayer = this.level().getNearestPlayer(this.getX(), this.getY(), this.getZ(), MAX_FOLLOW_DIST, predicate);
        }

        if (this.level() instanceof ServerLevel) {
            for (EntropyOrbEntity orb : this.level()
                    .getEntities(EntityTypeTest.forClass(EntropyOrbEntity.class), this.getBoundingBox().inflate(ORB_MERGE_DISTANCE), this::canMerge)) {
                merge(orb);
            }
        }
    }

    public static boolean isHoldingContainer(Player player) {
        return player.getMainHandItem().is(Registration.ENTROPY_CONTAINER_ITEM.get())
                || player.getOffhandItem().is(Registration.ENTROPY_CONTAINER_ITEM.get());
    }

    private boolean canMerge(EntropyOrbEntity orb) {
        return orb != this && !orb.isRemoved() && orb.getEntropyType() == this.getEntropyType();
    }

    private void merge(EntropyOrbEntity orb) {
        this.value += orb.value;
        this.age = Math.min(this.age, orb.age);
        orb.discard();
    }

    private void setUnderwaterMovement() {
        Vec3 delta = this.getDeltaMovement();
        this.setDeltaMovement(delta.x * 0.99F, Math.min(delta.y + 5.0E-4F, 0.06F), delta.z * 0.99F);
    }

    @Override
    protected void doWaterSplashEffect() {
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) return false;
        if (this.level().isClientSide) return true;
        this.markHurt();
        this.discard();
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        compound.putShort("Age", (short) this.age);
        compound.putInt("Value", this.value);
        compound.putBoolean("Chaos", this.getEntropyType() == EntropyType.CHAOS);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        this.age = compound.getShort("Age");
        this.value = compound.getInt("Value");
        setType(compound.getBoolean("Chaos") ? EntropyType.CHAOS : EntropyType.ORDER);
    }

    @Override
    public void playerTouch(Player entity) {
        if (!(this.level() instanceof ServerLevel)) return;
        if (!isHoldingContainer(entity)) return;

        ItemStack container = entity.getMainHandItem().is(Registration.ENTROPY_CONTAINER_ITEM.get())
                ? entity.getMainHandItem() : entity.getOffhandItem();

        int leftover = EntropyContainerItem.insert(container, getEntropyType(), this.value);
        int accepted = this.value - leftover;
        if (accepted <= 0) return;

        this.value = leftover;
        this.gameEvent(GameEvent.ENTITY_INTERACT, entity);
        this.level().playSound(null, this.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6F,
                0.8F + this.random.nextFloat() * 0.4F);
        if (this.value <= 0) {
            this.discard();
        }
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.AMBIENT;
    }
}
