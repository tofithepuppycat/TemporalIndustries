package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Holds no state of its own; it only keeps this lifeline in a registry of loaded lifelines so the
 * explosion handler can find them without scanning the world.
 */
public class TemporalLifelineBlockEntity extends BlockEntity implements EntropyInfoProvider {
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

    @Override
    public List<Component> getEntropyTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("block.temporalindustries.temporal_lifeline").withStyle(ChatFormatting.WHITE));
        AbstractTimelineMachineBlockEntity machine = level == null ? null : connectedMachine(level, worldPosition);
        if (machine == null) {
            lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.lifeline.unconnected").withStyle(ChatFormatting.RED));
            return lines;
        }
        lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.lifeline.connected", machine.getDisplayName())
                .withStyle(ChatFormatting.GREEN));
        lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.timeline.repository", machine.getRepositoryName())
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("overlay.temporalindustries.entropy_glasses.lifeline.chunks", machine.getAllChunks().size())
                .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    /** The first bound timeline machine touching pos with a face, or null if none does. */
    @Nullable
    private static AbstractTimelineMachineBlockEntity connectedMachine(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = pos.relative(dir);
            if (level.isLoaded(neighbour) && level.getBlockEntity(neighbour) instanceof AbstractTimelineMachineBlockEntity machine
                    && !machine.getRepositoryName().isEmpty()) {
                return machine;
            }
        }
        return null;
    }

    public static void clearRegistry() {
        LOADED.clear();
    }

    /**
     * Each repository currently protected by a loaded lifeline touching a timeline machine in level,
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

    /** The repository of any bound timeline machine touching pos with a face, or null if none does. */
    @Nullable
    public static UUID connectedRepository(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = pos.relative(dir);
            if (level.isLoaded(neighbour) && level.getBlockEntity(neighbour) instanceof AbstractTimelineMachineBlockEntity machine) {
                UUID repoId = machine.getRepositoryId();
                if (repoId != null) return repoId;
            }
        }
        return null;
    }
}
