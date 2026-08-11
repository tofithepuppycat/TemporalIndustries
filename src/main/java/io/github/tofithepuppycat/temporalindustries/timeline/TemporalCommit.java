package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * A single point in the timeline. SNAPSHOT commits store a full baseline state;
 * DELTA commits store only what changed since the previous commit.
 * Commits form a singly-linked chain via parentId (-1 for the root).
 */
public final class TemporalCommit {
    public enum Type { SNAPSHOT, DELTA, BRANCH }

    private final long id;
    private final long parentId;
    private final Type type;
    private final long gameTime;
    private final List<ChunkDelta> chunkDeltas;
    /** Only meaningful for Type.SNAPSHOT: a full per-chunk baseline (see {@link ChunkSnapshot}) for
     * every chunk this commit baselines. Never sent over the network — clients already see the
     * real blocks through normal chunk sync, so there's nothing for them to do with the raw grid. */
    private final List<ChunkSnapshot> chunkSnapshots;
    /** Only meaningful for Type.BRANCH: the packed ChunkPos (see {@link net.minecraft.world.level.ChunkPos#toLong})
     * this marker was checked out for. */
    private final long branchChunkPos;

    private TemporalCommit(long id, long parentId, Type type, long gameTime, List<ChunkDelta> chunkDeltas,
                           List<ChunkSnapshot> chunkSnapshots, long branchChunkPos) {
        this.id = id;
        this.parentId = parentId;
        this.type = type;
        this.gameTime = gameTime;
        this.chunkDeltas = Collections.unmodifiableList(new ArrayList<>(chunkDeltas));
        this.chunkSnapshots = Collections.unmodifiableList(new ArrayList<>(chunkSnapshots));
        this.branchChunkPos = branchChunkPos;
    }

    public static TemporalCommit delta(long id, long parentId, long gameTime, List<ChunkDelta> chunkDeltas) {
        return new TemporalCommit(id, parentId, Type.DELTA, gameTime, chunkDeltas, Collections.emptyList(), 0L);
    }

    /**
     * A full-baseline commit: {@link TemporalCommit#ancestryChain} stops here rather than walking
     * any further back, so a chunk's history-walk cost stays bounded by however recently it was
     * last snapshotted instead of growing for the life of the world.
     */
    public static TemporalCommit snapshot(long id, long parentId, long gameTime, List<ChunkSnapshot> chunkSnapshots) {
        return new TemporalCommit(id, parentId, Type.SNAPSHOT, gameTime, Collections.emptyList(), chunkSnapshots, 0L);
    }

    /**
     * A zero-diff marker commit created whenever a Time Machine checks out a point in time.
     * Scoped to branchChunkPos: it only records a fork in that one chunk's own history, so
     * subsequent deltas touching that chunk attach here instead of continuing past it, without
     * affecting any other chunk's timeline.
     */
    public static TemporalCommit branch(long id, long parentId, long gameTime, long branchChunkPos) {
        return new TemporalCommit(id, parentId, Type.BRANCH, gameTime, Collections.emptyList(), Collections.emptyList(), branchChunkPos);
    }

    public long getId() { return id; }
    public long getParentId() { return parentId; }
    public Type getType() { return type; }
    public long getGameTime() { return gameTime; }
    public List<ChunkDelta> getChunkDeltas() { return chunkDeltas; }
    /** Only meaningful when getType() == Type.SNAPSHOT. */
    public List<ChunkSnapshot> getChunkSnapshots() { return chunkSnapshots; }
    /** Only meaningful when getType() == Type.BRANCH. */
    public long getBranchChunkPos() { return branchChunkPos; }

    public int getTotalChangeCount() {
        int count = 0;
        for (ChunkDelta cd : chunkDeltas) count += cd.getChangeCount();
        return count;
    }

    /**
     * Deterministic 7-hex-digit label derived from this commit's identity, styled after a
     * git short hash. Not cryptographic — just a stable, distinct-looking tag for GUI display.
     */
    public String getShortHash() {
        long h = id * 0x9E3779B97F4A7C15L;
        h ^= Long.rotateLeft(parentId * 0xC2B2AE3D27D4EB4FL + 1L, 17);
        h ^= gameTime * 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return String.format("%07x", h & 0xFFFFFFFL);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putLong("ParentId", parentId);
        tag.putInt("Type", type.ordinal());
        tag.putLong("GameTime", gameTime);
        tag.putLong("BranchChunkPos", branchChunkPos);

        ListTag chunks = new ListTag();
        for (ChunkDelta cd : chunkDeltas) chunks.add(cd.toTag());
        tag.put("Chunks", chunks);

        ListTag snapshots = new ListTag();
        for (ChunkSnapshot snapshot : chunkSnapshots) snapshots.add(snapshot.toTag());
        tag.put("Snapshots", snapshots);

        return tag;
    }

    public static TemporalCommit fromTag(CompoundTag tag) {
        long id = tag.getLong("Id");
        long parentId = tag.getLong("ParentId");
        Type type = Type.values()[tag.getInt("Type")];
        long gameTime = tag.getLong("GameTime");
        long branchChunkPos = tag.getLong("BranchChunkPos");

        ListTag chunkList = tag.getList("Chunks", Tag.TAG_COMPOUND);
        List<ChunkDelta> chunks = new ArrayList<>();
        for (int i = 0; i < chunkList.size(); i++) chunks.add(ChunkDelta.fromTag(chunkList.getCompound(i)));

        List<ChunkSnapshot> snapshots = new ArrayList<>();
        if (tag.contains("Snapshots", Tag.TAG_LIST)) {
            ListTag snapshotList = tag.getList("Snapshots", Tag.TAG_COMPOUND);
            for (int i = 0; i < snapshotList.size(); i++) snapshots.add(ChunkSnapshot.fromTag(snapshotList.getCompound(i)));
        }

        return new TemporalCommit(id, parentId, type, gameTime, chunks, snapshots, branchChunkPos);
    }

    public static void encode(TemporalCommit commit, FriendlyByteBuf buf) {
        buf.writeLong(commit.id);
        buf.writeLong(commit.parentId);
        buf.writeVarInt(commit.type.ordinal());
        buf.writeLong(commit.gameTime);
        buf.writeLong(commit.branchChunkPos);

        buf.writeVarInt(commit.chunkDeltas.size());
        for (ChunkDelta cd : commit.chunkDeltas) ChunkDelta.encode(cd, buf);
    }

    public static TemporalCommit decode(FriendlyByteBuf buf) {
        long id = buf.readLong();
        long parentId = buf.readLong();
        Type type = Type.values()[buf.readVarInt()];
        long gameTime = buf.readLong();
        long branchChunkPos = buf.readLong();

        int chunkCount = buf.readVarInt();
        List<ChunkDelta> chunks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) chunks.add(ChunkDelta.decode(buf));

        return new TemporalCommit(id, parentId, type, gameTime, chunks, Collections.emptyList(), branchChunkPos);
    }

    // -------------------------------------------------------------------------
    // Shared chunk-scoped graph traversal — used identically by the server (TemporalTimeline,
    // to actually resolve/apply a rollback) and the client (TimelineProjectionManager, to preview
    // one) so the two can never compute a different result for the same chunk-scoped commit list.

    /** The commit in candidates whose gameTime is nearest targetGameTime, or -1 if candidates is
     * empty. On an exact tie, prefers the LATER candidate (by list position), since a branch marker
     * shares its exact gameTime with the commit it forked from and always appears after it. */
    public static long resolveNearest(List<TemporalCommit> candidates, long targetGameTime) {
        long bestId = -1L;
        long bestDist = Long.MAX_VALUE;
        for (TemporalCommit c : candidates) {
            long dist = Math.abs(c.getGameTime() - targetGameTime);
            if (dist <= bestDist) {
                bestDist = dist;
                bestId = c.getId();
            }
        }
        return bestId;
    }

    /** Reconstructs commitId's ancestry within a chunk (root-most first, commitId last) by
     * following localParentById links (each chunk-relevant commit id -> the id it locally forked
     * from, -1/absent = root). Recorded explicitly at commit time rather than inferred from list
     * order (see {@link TemporalTimeline}). Stops at the nearest SNAPSHOT ancestor rather than
     * always walking to the true root — a snapshot is a full baseline (see {@link ChunkSnapshot}),
     * so nothing before it can affect a diff computed from this chain, and without this bound the
     * walk (done identically by both the server and the client's ghost-preview copy of this method)
     * would grow for the entire life of the world. */
    public static List<TemporalCommit> ancestryChain(List<TemporalCommit> chunkCommits, Map<Long, Long> localParentById, long commitId) {
        Map<Long, TemporalCommit> byId = new HashMap<>();
        for (TemporalCommit c : chunkCommits) byId.put(c.getId(), c);

        LinkedList<TemporalCommit> chain = new LinkedList<>();
        long currentId = commitId;
        while (currentId >= 0) {
            TemporalCommit commit = byId.get(currentId);
            if (commit == null) break;
            chain.addFirst(commit);
            if (commit.getType() == Type.SNAPSHOT) break;
            currentId = localParentById.getOrDefault(currentId, -1L);
        }
        return chain;
    }

    /** Whether any commit in localParentById has parentId recorded as its local parent. */
    public static boolean hasChild(Map<Long, Long> localParentById, long parentId) {
        return localParentById.containsValue(parentId);
    }
}
