package io.github.tofithepuppycat.temporalindustries.block.entity;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChunkArea;
import io.github.tofithepuppycat.temporalindustries.data.TemporalWorldData;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.timeline.TemporalRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Block entity for the Chronosphere: the player claims up to an 11x11 chunk area (centred on and
 * always including the home chunk), and a single jump moves every claimed chunk to the same target
 * time, paid from one shared energy pool.
 */
public class ChronosphereBlockEntity extends AbstractTimelineMachineBlockEntity {
    /**
     * Chunks may be claimed up to this many steps from the home chunk on either axis, i.e. an 11x11
     * box.
     */
    public static final int MAX_RADIUS = 5;
    /** Home chunk plus up to this many additional chunks = 121 chunks, a full 11x11 box. */
    public static final int MAX_ADDITIONAL_CHUNKS = (MAX_RADIUS * 2 + 1) * (MAX_RADIUS * 2 + 1) - 1;

    private static final int ENERGY_CAPACITY = 500_000;
    private static final int ENERGY_TRANSFER  = 1_000_000_000;
    private static final int CREATIVE_ENERGY_CAPACITY = Integer.MAX_VALUE;

    public enum ToggleResult { ADDED, REMOVED, IS_HOME, OUT_OF_BOUNDS, ALREADY_CLAIMED_ELSEWHERE, LIMIT_REACHED, NOT_SELECTED, REGION_CHUNK }

    public enum RegionResult { ADDED, NOT_BOUND, OUT_OF_BOUNDS, CLAIMED_ELSEWHERE }

    /** Claimed chunks beyond the home chunk (which is always implicitly included). */
    private final Set<Long> additionalChunks = new LinkedHashSet<>();

    public ChronosphereBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(Registration.CHRONOSPHERE_BLOCK_ENTITY.get(), blockPos, blockState,
                blockState.is(Registration.CREATIVE_CHRONOSPHERE_BLOCK.get()) ? CREATIVE_ENERGY_CAPACITY : ENERGY_CAPACITY,
                ENERGY_TRANSFER);
    }

    @Override
    protected boolean isCreative() {
        return getBlockState().is(Registration.CREATIVE_CHRONOSPHERE_BLOCK.get());
    }

    /** A freshly placed Chronosphere has no repository until a player names one. */
    @Override
    protected boolean mintsRepositoryOnDemand() {
        return false;
    }

    /** Re-asserts this machine's claim on its home + additional chunks. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) claimOwnChunks();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        releaseOwnChunks();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ChronosphereBlockEntity be) {
        if (level.isClientSide || !be.isRepositoryBound()) return;
        be.commonTick(level);
    }

    public ChunkPos getHomeChunkPos() {
        return new ChunkPos(worldPosition);
    }

    public boolean isWithinBounds(ChunkPos pos) {
        ChunkPos home = getHomeChunkPos();
        return isWithinRadius(pos.x - home.x, pos.z - home.z);
    }

    /**
     * The claimable area's outline: a full square box, so every cell of the map grid is claimable
     * (the Portable Chrono Marker's area-select map keeps the inscribed {@code CIRCLE} instead).
     */
    public static final ChunkArea.Shape CLAIM_SHAPE = ChunkArea.Shape.SQUARE;

    /**
     * Whether a chunk offset (dx, dz) from the home chunk falls within the claimable box; shared
     * with the client screen and network handlers so shape, hit-testing, and server rule always
     * agree.
     */
    public static boolean isWithinRadius(int dx, int dz) {
        return CLAIM_SHAPE.contains(MAX_RADIUS, dx, dz);
    }

    /** Every chunk this machine currently controls, home chunk first. */
    @Override
    public List<ChunkPos> getAllChunks() {
        List<ChunkPos> chunks = new ArrayList<>(additionalChunks.size() + 1);
        chunks.add(getHomeChunkPos());
        for (long key : additionalChunks) chunks.add(new ChunkPos(key));
        TemporalRepository repo = getRepository();
        if (repo != null) {
            for (long key : repo.getRegionChunks()) {
                if (!additionalChunks.contains(key)) chunks.add(new ChunkPos(key));
            }
        }
        return chunks;
    }

    public Set<Long> getAdditionalChunkKeys() {
        return additionalChunks;
    }

    /** Claims or releases pos for this machine's repository. */
    public ToggleResult toggleChunk(ChunkPos pos, boolean add) {
        UUID repoId = getRepositoryId();
        if (repoId == null || !(level instanceof ServerLevel serverLevel) || level.getServer() == null) return ToggleResult.OUT_OF_BOUNDS;

        ChunkPos home = getHomeChunkPos();
        if (pos.equals(home)) return ToggleResult.IS_HOME;
        if (!isWithinBounds(pos)) return ToggleResult.OUT_OF_BOUNDS;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        TemporalRepository repo = getRepository();
        long key = pos.toLong();
        boolean regionChunk = repo != null && repo.getRegionChunks().contains(key);

        if (add) {
            if (additionalChunks.contains(key)) return ToggleResult.ADDED;
            if (additionalChunks.size() >= MAX_ADDITIONAL_CHUNKS) return ToggleResult.LIMIT_REACHED;

            if (regionChunk) {
                // Already claimed for a glued region; claiming it outright tracks all of it.
                repo.removeRegionChunk(key);
                additionalChunks.add(key);
                worldData.setDirty();
                setChanged();
                return ToggleResult.ADDED;
            }

            if (worldData.claimChunk(dimension, pos, repoId)
                    == TemporalWorldData.ClaimResult.ALREADY_CLAIMED_ELSEWHERE) {
                return ToggleResult.ALREADY_CLAIMED_ELSEWHERE;
            }

            additionalChunks.add(key);
            if (repo != null) repo.captureClaimBaseline(serverLevel, pos);
            worldData.setDirty();
            setChanged();
            return ToggleResult.ADDED;
        } else {
            if (regionChunk) {
                return releaseRegionChunkIfUnused(repo, worldData, dimension, repoId, pos)
                        ? ToggleResult.REMOVED : ToggleResult.REGION_CHUNK;
            }
            if (!additionalChunks.remove(key)) return ToggleResult.NOT_SELECTED;
            worldData.releaseChunk(dimension, pos, repoId);
            worldData.setDirty();
            setChanged();
            return ToggleResult.REMOVED;
        }
    }

    /**
     * Marks region as tracked by this machine's repository, claiming whichever of the chunks it
     * spans aren't claimed yet. Those chunks are only tracked inside the region.
     */
    public RegionResult addTrackedRegion(BoundingBox region) {
        UUID repoId = getRepositoryId();
        TemporalRepository repo = getRepository();
        if (repoId == null || repo == null || !(level instanceof ServerLevel serverLevel) || level.getServer() == null) {
            return RegionResult.NOT_BOUND;
        }

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();

        List<ChunkPos> unclaimed = new ArrayList<>();
        for (int chunkX = region.minX() >> 4; chunkX <= region.maxX() >> 4; chunkX++) {
            for (int chunkZ = region.minZ() >> 4; chunkZ <= region.maxZ() >> 4; chunkZ++) {
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                if (!isWithinBounds(chunk)) return RegionResult.OUT_OF_BOUNDS;
                UUID owner = worldData.getClaimingRepo(dimension, chunk);
                if (owner == null) unclaimed.add(chunk);
                else if (!owner.equals(repoId)) return RegionResult.CLAIMED_ELSEWHERE;
            }
        }

        for (ChunkPos chunk : unclaimed) {
            worldData.claimChunk(dimension, chunk, repoId);
            repo.addRegionChunk(chunk.toLong());
            repo.captureClaimBaseline(serverLevel, chunk);
        }
        repo.addTrackedRegion(region);
        worldData.setDirty();
        setChanged();
        return RegionResult.ADDED;
    }

    /** Removes every tracked region the segment from origin to end passes through; returns how many. */
    public int removeTrackedRegionsAlongRay(Vec3 origin, Vec3 end) {
        UUID repoId = getRepositoryId();
        TemporalRepository repo = getRepository();
        if (repoId == null || repo == null || level == null || level.getServer() == null) return 0;

        int removed = repo.removeTrackedRegions(region -> new AABB(region.minX(), region.minY(), region.minZ(),
                region.maxX() + 1, region.maxY() + 1, region.maxZ() + 1).clip(origin, end).isPresent());
        if (removed == 0) return 0;

        TemporalWorldData worldData = TemporalWorldData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        for (long key : new ArrayList<>(repo.getRegionChunks())) {
            releaseRegionChunkIfUnused(repo, worldData, dimension, repoId, new ChunkPos(key));
        }
        worldData.setDirty();
        setChanged();
        return removed;
    }

    /**
     * Gives up a chunk that was claimed only for glued regions once none of them touches it. A
     * chunk with recorded history stays: a jump would otherwise restore all of it.
     */
    private boolean releaseRegionChunkIfUnused(TemporalRepository repo, TemporalWorldData worldData,
                                               ResourceLocation dimension, UUID repoId, ChunkPos chunk) {
        for (BoundingBox region : repo.getTrackedRegions()) {
            if (region.minX() >> 4 <= chunk.x && chunk.x <= region.maxX() >> 4
                    && region.minZ() >> 4 <= chunk.z && chunk.z <= region.maxZ() >> 4) {
                return false;
            }
        }
        if (repo.hasBaseline(chunk)) return false;

        repo.removeRegionChunk(chunk.toLong());
        worldData.releaseChunk(dimension, chunk, repoId);
        worldData.setDirty();
        setChanged();
        return true;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.temporalindustries.chronosphere");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory playerInventory, @NotNull Player player) {
        return new ChronosphereMenu(id, playerInventory, this,
                ContainerLevelAccess.create(Objects.requireNonNull(level), worldPosition), getContainerData());
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        ListTag chunkList = new ListTag();
        for (long key : additionalChunks) chunkList.add(LongTag.valueOf(key));
        tag.put("AdditionalChunks", chunkList);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        additionalChunks.clear();
        ListTag chunkList = tag.getList("AdditionalChunks", Tag.TAG_LONG);
        for (int i = 0; i < chunkList.size(); i++) {
            if (chunkList.get(i) instanceof LongTag longTag) additionalChunks.add(longTag.getAsLong());
        }
    }
}
