package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyOrbEntity;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Early-game passive CHS generator: while it holds a captured mob (see
 * {@link io.github.tofithepuppycat.temporalindustries.item.SchrodingersBoxItem}), it periodically
 * spawns a small {@link EntropyOrbEntity}. The mob itself is never simulated — only its type and
 * custom name are kept — and is respawned back into the world by {@link #release()}, called either
 * from an empty-handed right-click ({@code SchrodingersBox.useWithoutItem}) or when the block is
 * removed ({@code SchrodingersBox.onRemove}), so breaking an occupied box never silently deletes
 * the mob.
 */
@SuppressWarnings("null")
public class SchrodingersBoxBlockEntity extends BlockEntity {
    private static final int CHS_INTERVAL_TICKS = 100;
    private static final int CHS_PER_INTERVAL = 1;

    @Nullable private ResourceLocation capturedTypeId;
    @Nullable private Component capturedName;

    public SchrodingersBoxBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.SCHRODINGERS_BOX_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean isOccupied() {
        return capturedTypeId != null;
    }

    public void capture(ResourceLocation typeId, @Nullable Component name) {
        this.capturedTypeId = typeId;
        this.capturedName = name;
        setChanged();
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
    }

    public static void tick(Level level, BlockPos pos, BlockState state, SchrodingersBoxBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel) || !be.isOccupied()) return;
        if (level.getGameTime() % CHS_INTERVAL_TICKS != 0) return;

        EntropyOrbEntity.spawn(serverLevel, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                EntropyType.CHAOS, CHS_PER_INTERVAL);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (capturedTypeId != null) tag.putString("CapturedType", capturedTypeId.toString());
        if (capturedName != null) tag.putString("CapturedName", Component.Serializer.toJson(capturedName, registries));
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        capturedTypeId = tag.contains("CapturedType") ? ResourceLocation.parse(tag.getString("CapturedType")) : null;
        capturedName = tag.contains("CapturedName") ? Component.Serializer.fromJson(tag.getString("CapturedName"), registries) : null;
    }
}
