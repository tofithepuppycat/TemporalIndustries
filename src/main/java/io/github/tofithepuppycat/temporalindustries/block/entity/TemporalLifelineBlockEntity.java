package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Holds no state of its own; it only keeps this lifeline in a registry of loaded lifelines so the
 * explosion handler can find them without scanning the world.
 */
public class TemporalLifelineBlockEntity extends BlockEntity {
    private static final Set<GlobalPos> LOADED = new HashSet<>();

    public TemporalLifelineBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.TEMPORAL_LIFELINE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) LOADED.add(GlobalPos.of(serverLevel.dimension(), worldPosition));
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) LOADED.remove(GlobalPos.of(serverLevel.dimension(), worldPosition));
    }

    public static void clearRegistry() {
        LOADED.clear();
    }

    /**
     * Each repository currently protected by a loaded lifeline touching a Chronosphere in level,
     * mapped to one such lifeline's position (the beam origin).
     */
    public static Map<UUID, BlockPos> protectingLifelines(ServerLevel level) {
        Map<UUID, BlockPos> result = new HashMap<>();
        for (GlobalPos global : LOADED) {
            if (!global.dimension().equals(level.dimension())) continue;
            UUID repoId = connectedRepository(level, global.pos());
            if (repoId != null) result.putIfAbsent(repoId, global.pos());
        }
        return result;
    }

    /** The repository of any bound Chronosphere touching pos with a face, or null if none does. */
    @Nullable
    public static UUID connectedRepository(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = pos.relative(dir);
            if (level.isLoaded(neighbour) && level.getBlockEntity(neighbour) instanceof ChronosphereBlockEntity sphere) {
                UUID repoId = sphere.getRepositoryId();
                if (repoId != null) return repoId;
            }
        }
        return null;
    }
}
