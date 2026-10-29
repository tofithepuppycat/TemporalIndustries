package io.github.tofithepuppycat.temporalindustries.timeline;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * A jump carried out a slice at a time, so a claim of any size costs the server only what each
 * tick's deadline allows. The smallest unit of work is one 16x16x16 section of one chunk, and
 * whichever chunk lies nearest a player is always worked on first, however slow it turns out to be.
 * <p>
 * Phases: {@code ESTIMATING} measures what the jump would cost (skipped for a prepaid jump),
 * handing the result to {@link Options#onEstimated}, which either {@link #proceed}s or cancels;
 * {@code APPLYING} writes every affected chunk; {@code SETTLING} runs the neighbour updates once
 * every chunk is written, so none sees a sibling chunk in its pre-jump state; {@code ENTITIES}
 * restores entities. The repository's head only moves once all of that is done.
 */
public final class JumpJob {
    public enum Phase { ESTIMATING, WAITING, APPLYING, SETTLING, ENTITIES, DONE, CANCELLED }

    /**
     * Raw totals of an estimate pass; the caller turns them into energy using its own pricing.
     * containerNet is the net gain per item across every affected container.
     */
    public record Estimate(long blockCost, long changedBlocks, Map<Item, Long> containerNet,
                           int entityCount, double entityHealth) {}

    /**
     * What a job needs from the machine running it.
     *
     * @param applies whether this jump is meant to rewrite the world; an estimate-only job never does
     * @param loadedOnly leave unloaded chunks out of the estimate instead of loading them
     * @param skipEstimate go straight to rewriting the world (the cost was settled beforehand)
     * @param onEstimated called once the estimate is ready, with the job waiting for {@link #proceed}
     *                    or {@link #cancel}; a job left waiting is cancelled
     * @param onFinished called once the head has moved
     */
    public record Options(Predicate<BlockPos> isGlued, ToLongFunction<BlockState> blockCost, boolean restoreEntities,
                          boolean countContainers, boolean loadedOnly, boolean skipEstimate, boolean applies,
                          Consumer<JumpJob> onEstimated, Consumer<JumpJob> onFinished) {}

    /** Neighbour updates run per unit of work. */
    private static final int SETTLE_BATCH = 128;

    private static final class WorkChunk {
        final long key;
        @Nullable ResolvedChunk resolved;
        int[] sections = new int[0];
        int cursor;
        double distance;

        WorkChunk(long key) {
            this.key = key;
        }
    }

    private final TemporalRepository repo;
    private final ServerLevel level;
    private final TemporalRepository.JumpPlan plan;
    private final ChunkPos origin;
    private final Options options;

    private Phase phase;
    private final List<WorkChunk> pending = new ArrayList<>();

    private boolean entitiesResolved;
    private Map<UUID, CompoundTag> desiredEntities = Map.of();
    @Nullable private Iterator<Map.Entry<UUID, CompoundTag>> entityIterator;
    private int entityCount;
    private double entityHealth;

    private long blockCost;
    private long changedBlocks;
    private final Map<Item, Long> containerNet = new HashMap<>();
    @Nullable private Estimate estimate;

    /** Every position the apply phase replaced, packed with {@link BlockPos#asLong}. */
    private final LongArrayList changed = new LongArrayList();
    private int settleIndex;

    JumpJob(TemporalRepository repo, ServerLevel level, TemporalRepository.JumpPlan plan, ChunkPos origin, Options options) {
        this.repo = repo;
        this.level = level;
        this.plan = plan;
        this.origin = origin;
        this.options = options;
        this.phase = options.skipEstimate() ? Phase.APPLYING : Phase.ESTIMATING;
        queueChunks();
    }

    public Phase phase() { return phase; }
    public boolean isOver() { return phase == Phase.DONE || phase == Phase.CANCELLED; }
    public boolean applies() { return options.applies(); }
    public long targetCommitId() { return plan.toCommitId; }
    public long fromCommitId() { return plan.fromCommitId; }

    /** The estimate, available from the moment {@link Options#onEstimated} is called. */
    public Estimate estimate() {
        return estimate;
    }

    /** Every position written so far, packed with {@link BlockPos#asLong}. */
    public LongArrayList changedPositions() {
        return changed;
    }

    /** Lets a job that is waiting on its estimate go on to rewrite the world. */
    public void proceed() {
        if (phase != Phase.WAITING || !options.applies()) return;
        phase = Phase.APPLYING;
        queueChunks();
    }

    public void cancel() {
        if (!isOver()) phase = Phase.CANCELLED;
    }

    private void queueChunks() {
        pending.clear();
        for (long key : plan.chunks) pending.add(new WorkChunk(key));
    }

    /**
     * Works until deadlineNanos ({@link System#nanoTime} scale) has passed. With mustProgress the
     * first unit of work always runs, so a job cannot starve behind an already-spent deadline.
     * Returns whether the job is over.
     */
    public boolean step(long deadlineNanos, boolean mustProgress) {
        if (phase == Phase.ESTIMATING || phase == Phase.APPLYING) prioritise();

        boolean first = mustProgress;
        while (!isOver()) {
            if (!first && System.nanoTime() >= deadlineNanos) return false;
            first = false;
            switch (phase) {
                case ESTIMATING -> estimateUnit();
                case APPLYING -> applyUnit();
                case SETTLING -> settleUnit();
                case ENTITIES -> entityUnit();
                default -> cancel();
            }
        }
        return true;
    }

    /** Orders the remaining chunks nearest-first from where the players are, ignoring how costly each is. */
    private void prioritise() {
        List<ServerPlayer> players = level.players();
        for (WorkChunk chunk : pending) chunk.distance = distanceSqr(chunk.key, players);
        pending.sort(Comparator.comparingDouble(chunk -> chunk.distance));
    }

    private double distanceSqr(long chunkKey, List<ServerPlayer> players) {
        double centerX = ChunkPos.getX(chunkKey) * 16.0 + 8.0;
        double centerZ = ChunkPos.getZ(chunkKey) * 16.0 + 8.0;
        if (players.isEmpty()) {
            double dx = centerX - (origin.x * 16.0 + 8.0);
            double dz = centerZ - (origin.z * 16.0 + 8.0);
            return dx * dx + dz * dz;
        }
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            double dx = centerX - player.getX();
            double dz = centerZ - player.getZ();
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    private void resolve(WorkChunk chunk) {
        if (chunk.resolved != null) return;
        chunk.resolved = repo.resolveChunk(plan, chunk.key, level);
        chunk.sections = chunk.resolved.sectionsToVisit();
    }

    private void estimateUnit() {
        if (options.restoreEntities() && !entitiesResolved) {
            resolveEntities();
            return;
        }
        if (pending.isEmpty()) {
            finishEstimate();
            return;
        }

        WorkChunk work = pending.get(0);
        int chunkX = ChunkPos.getX(work.key);
        int chunkZ = ChunkPos.getZ(work.key);
        LevelChunk live = options.loadedOnly() ? level.getChunkSource().getChunkNow(chunkX, chunkZ) : level.getChunk(chunkX, chunkZ);
        if (live == null) {
            pending.remove(0);
            return;
        }

        resolve(work);
        ResolvedChunk resolved = work.resolved;
        if (work.cursor < work.sections.length) {
            for (BlockPos pos : resolved.changesInSection(work.sections[work.cursor++], live, options.isGlued())) {
                BlockState desired = resolved.targetState(pos);
                if (live.getBlockState(pos).equals(desired)) continue;
                changedBlocks++;
                blockCost += options.blockCost().applyAsLong(desired);
            }
            return;
        }

        if (options.countContainers()) tallyContainers(resolved, live);
        pending.remove(0);
    }

    private void tallyContainers(ResolvedChunk resolved, LevelChunk live) {
        for (Map.Entry<BlockPos, CompoundTag> entry : resolved.blockEntityTags().entrySet()) {
            if (entry.getValue() == null || options.isGlued().test(entry.getKey())) continue;
            ContainerContents.tally(entry.getValue(), containerNet, 1);
        }
        for (Map.Entry<BlockPos, BlockEntity> entry : live.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            if (!resolved.covers(pos) || options.isGlued().test(pos)) continue;
            ContainerContents.tally(entry.getValue().saveWithoutMetadata(level.registryAccess()), containerNet, -1);
        }
    }

    private void resolveEntities() {
        desiredEntities = repo.resolveJumpEntities(plan);
        double health = 0.0;
        for (Map.Entry<UUID, CompoundTag> entry : desiredEntities.entrySet()) {
            CompoundTag tag = entry.getValue();
            if (tag != null) {
                health += tag.getFloat("Health");
            } else if (level.getEntity(entry.getKey()) instanceof LivingEntity living) {
                health += living.getHealth();
            }
        }
        entityCount = desiredEntities.size();
        entityHealth = health;
        entitiesResolved = true;
    }

    private void finishEstimate() {
        estimate = new Estimate(blockCost, changedBlocks, containerNet, entityCount, entityHealth);
        phase = Phase.WAITING;
        options.onEstimated().accept(this);
        if (phase == Phase.WAITING) cancel();
    }

    private void applyUnit() {
        if (pending.isEmpty()) {
            phase = Phase.SETTLING;
            return;
        }

        WorkChunk work = pending.get(0);
        resolve(work);
        if (work.cursor >= work.sections.length) {
            pending.remove(0);
            return;
        }

        LevelChunk live = level.getChunk(ChunkPos.getX(work.key), ChunkPos.getZ(work.key));
        List<BlockPos> positions = work.resolved.changesInSection(work.sections[work.cursor++], live, options.isGlued());
        TemporalRepository.applyChanges(work.resolved, positions, level, packed -> changed.add(packed));
    }

    private void settleUnit() {
        int end = Math.min(changed.size(), settleIndex + SETTLE_BATCH);
        for (; settleIndex < end; settleIndex++) {
            TemporalRepository.settleBlock(level, BlockPos.of(changed.getLong(settleIndex)));
        }
        if (settleIndex >= changed.size()) phase = Phase.ENTITIES;
    }

    private void entityUnit() {
        if (!options.restoreEntities()) {
            finish();
            return;
        }
        if (!entitiesResolved) resolveEntities();
        if (entityIterator == null) entityIterator = desiredEntities.entrySet().iterator();
        if (!entityIterator.hasNext()) {
            finish();
            return;
        }
        Map.Entry<UUID, CompoundTag> entry = entityIterator.next();
        TemporalRepository.applyEntityState(entry.getKey(), entry.getValue(), level);
    }

    private void finish() {
        repo.completeJump(plan.toCommitId);
        phase = Phase.DONE;
        options.onFinished().accept(this);
    }
}
