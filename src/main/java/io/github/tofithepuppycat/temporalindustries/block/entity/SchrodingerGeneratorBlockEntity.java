package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Early-game passive entropy generator: while it holds a captured mob (see
 * {@link io.github.tofithepuppycat.temporalindustries.item.SchrodingerGeneratorItem}), it periodically
 * spawns a small {@link EntropyOrbEntity}, ORD or CHS with equal chance each time — the mob is both
 * alive and dead until observed. Landing on ORD also fires a pulse of FE into {@link #energyStorage},
 * as if collapsing the superposition into "alive" released a burst of energy. The mob itself is never
 * simulated — only its type and custom name are kept — and is respawned back into the world by
 * {@link #release()}, called either from an empty-handed right-click
 * ({@code SchrodingerGenerator.useWithoutItem}) or when the block is removed
 * ({@code SchrodingerGenerator.onRemove}), so breaking an occupied generator never silently deletes
 * the mob.
 */
@SuppressWarnings("null")
public class SchrodingerGeneratorBlockEntity extends BlockEntity implements EntropyInfoProvider {
    // Interval halved and CHS_PER_INTERVAL halved to match, so the generator drops orbs twice as often
    // without producing entropy any faster overall; entropyAccumulator carries the fractional
    // remainder between checks since an orb can't be spawned with a value below 1.
    private static final int CHS_INTERVAL_TICKS = 50;
    private static final double CHS_PER_INTERVAL = 0.5;

    private static final int ENERGY_CAPACITY = 4_000;
    private static final int ENERGY_MAX_EXTRACT = 400;
    private static final int ORDER_PULSE_ENERGY = 800;

    private final class GeneratorEnergyStorage extends EnergyStorage {
        GeneratorEnergyStorage() {
            super(ENERGY_CAPACITY, 0, ENERGY_MAX_EXTRACT);
        }

        @Override public int extractEnergy(int max, boolean simulate) {
            int v = super.extractEnergy(max, simulate);
            if (!simulate && v > 0) setChanged();
            return v;
        }

        void produce(int amount) {
            if (amount <= 0) return;
            energy = Math.min(capacity, energy + amount);
            setChanged();
        }
    }

    @Nullable private ResourceLocation capturedTypeId;
    @Nullable private Component capturedName;
    private double entropyAccumulator = 0.0;
    private final GeneratorEnergyStorage energyStorage = new GeneratorEnergyStorage();

    public SchrodingerGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.SCHRODINGER_GENERATOR_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean isOccupied() {
        return capturedTypeId != null;
    }

    public IEnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    @Override
    public List<Component> getEntropyTooltip() {
        return List.of(isOccupied()
                ? Component.translatable("overlay.temporalindustries.entropy_glasses.schrodinger_generator.generating").withStyle(ChatFormatting.DARK_PURPLE)
                : Component.translatable("overlay.temporalindustries.entropy_glasses.schrodinger_generator.empty").withStyle(ChatFormatting.GRAY));
    }

    public void capture(ResourceLocation typeId, @Nullable Component name) {
        this.capturedTypeId = typeId;
        this.capturedName = name;
        setChanged();
        syncToClients();
    }

    /** Spawns the stored mob back into the world above this block and clears the capture, if any. */
    public void release() {
        if (level == null || level.isClientSide || capturedTypeId == null) return;

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(capturedTypeId);
        Entity entity = type.create(level);
        if (entity != null) {
            entity.moveTo(worldPosition.getX() + 0.5, worldPosition.getY() + 0.2, worldPosition.getZ() + 0.5, 0.0F, 0.0F);
            if (capturedName != null) entity.setCustomName(capturedName);
            level.addFreshEntity(entity);
        }
        capturedTypeId = null;
        capturedName = null;
        setChanged();
        syncToClients();
    }

    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, SchrodingerGeneratorBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel) || !be.isOccupied()) return;
        if (level.getGameTime() % CHS_INTERVAL_TICKS != 0) return;

        be.entropyAccumulator += CHS_PER_INTERVAL;
        int toSpawn = (int) Math.floor(be.entropyAccumulator);
        if (toSpawn <= 0) return;
        be.entropyAccumulator -= toSpawn;

        EntropyType type = serverLevel.getRandom().nextBoolean() ? EntropyType.ORDER : EntropyType.CHAOS;
        if (type == EntropyType.ORDER) be.energyStorage.produce(ORDER_PULSE_ENERGY);
        EntropyOrbEntity.spawn(serverLevel, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, type, toSpawn);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (capturedTypeId != null) tag.putString("CapturedType", capturedTypeId.toString());
        if (capturedName != null) tag.putString("CapturedName", Component.Serializer.toJson(capturedName, registries));
        tag.putDouble("ChsAccumulator", entropyAccumulator);
        tag.put("Energy", energyStorage.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        capturedTypeId = tag.contains("CapturedType") ? ResourceLocation.parse(tag.getString("CapturedType")) : null;
        capturedName = tag.contains("CapturedName") ? Component.Serializer.fromJson(tag.getString("CapturedName"), registries) : null;
        entropyAccumulator = tag.getDouble("ChsAccumulator");
        if (tag.contains("Energy")) energyStorage.deserializeNBT(registries, tag.get("Energy"));
    }
}
