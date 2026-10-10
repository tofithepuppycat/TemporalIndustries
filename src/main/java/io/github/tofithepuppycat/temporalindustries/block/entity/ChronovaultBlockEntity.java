package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.timeline.RepoCommit;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import io.github.tofithepuppycat.temporalindustries.menu.ChronovaultMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Block entity for the Chronovault: holds per-machine energy/time state and owns a
 * {@link TemporalRepository} covering its own single chunk. The repository-backed timeline view is
 * entirely inherited from {@link AbstractTimelineMachineBlockEntity}; the only thing this class
 * decides is which chunk it claims. */
public class ChronovaultBlockEntity extends AbstractTimelineMachineBlockEntity {
    private static final int ENERGY_CAPACITY = 100_000;
    private static final int ENERGY_TRANSFER  = 1_000;

    public ChronovaultBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(Registration.CHRONOVAULT_BLOCK_ENTITY.get(), blockPos, blockState, ENERGY_CAPACITY, ENERGY_TRANSFER);
    }

    private ChunkPos getChunkPos() {
        return new ChunkPos(worldPosition);
    }

    @Override
    public List<ChunkPos> getAllChunks() {
        return List.of(getChunkPos());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!(level instanceof ServerLevel)) return;

        claimOwnChunks();

        // Restore placedGameTime from the repository's root commit on reload, so a reloaded machine
        // doesn't clamp its own history out of the selectable window.
        if (placedGameTime == UNSET_TIME) {
            TemporalRepository repo = getRepository();
            RepoCommit root = repo == null ? null : repo.getCommit(repo.getRootCommitId());
            if (root != null) {
                placedGameTime = root.getGameTime();
                selectedGameTime = Math.max(placedGameTime, selectedGameTime == UNSET_TIME ? placedGameTime : selectedGameTime);
                setChanged();
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        releaseOwnChunks();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ChronovaultBlockEntity be) {
        if (level.isClientSide) return;
        be.commonTick(level);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.temporalindustries.chronovault");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory playerInventory, @NotNull Player player) {
        return new ChronovaultMenu(id, playerInventory, this,
                ContainerLevelAccess.create(Objects.requireNonNull(level), worldPosition), getContainerData());
    }
}
