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
 * A single point in the timeline. SNAPSHOT commits store a full baseline state; DELTA commits
 * store only what changed since the previous commit. Both types can also come from a player's
 * Portable ChronoMarker save, flagged via playerMarked. Commits form a singly-linked chain via
 * parentId (-1 for the root).
 */
public final class TemporalCommit {
    public enum Type { SNAPSHOT, DELTA, BRANCH }

    /** Sentinel meaning "no specific commit preferred — resolve purely by gameTime". */
    public static final long NO_PREFERRED_COMMIT = -1L;

    private final long id;
    private final long parentId;
    private final Type type;
    private final long gameTime;
    private final List<ChunkDelta> chunkDeltas;
    /** Only meaningful for Type.SNAPSHOT: a full per-chunk baseline for every chunk this commit
     * baselines. Never sent over the network. */
    private final List<ChunkSnapshot> chunkSnapshots;
    /** Only meaningful for Type.BRANCH: the packed ChunkPos this marker was checked out for. */
    private final long branchChunkPos;
    /** Whether a player's Portable ChronoMarker save produced this DELTA/SNAPSHOT, rather than
     * automatic tracking. Never true for BRANCH. */
    private final boolean playerMarked;

    private TemporalCommit(long id, long parentId, Type type, long gameTime, List<ChunkDelta> chunkDeltas,
                           List<ChunkSnapshot> chunkSnapshots, long branchChunkPos, boolean playerMarked) {
        this.id = id;
        this.parentId = parentId;
        this.type = type;
        this.gameTime = gameTime;
        this.chunkDeltas = Collections.unmodifiableList(new ArrayList<>(chunkDeltas));
        this.chunkSnapshots = Collections.unmodifiableList(new ArrayList<>(chunkSnapshots));
        this.branchChunkPos = branchChunkPos;
        this.playerMarked = playerMarked;
    }

    public static TemporalCommit delta(long id, long parentId, long gameTime, List<ChunkDelta> chunkDeltas, boolean playerMarked) {
        return new TemporalCommit(id, parentId, Type.DELTA, gameTime, chunkDeltas, Collections.emptyList(), 0L, playerMarked);
    }

    /** A full-baseline commit; {@link #ancestryChain} stops here instead of walking further back. */
    public static TemporalCommit snapshot(long id, long parentId, long gameTime, List<ChunkSnapshot> chunkSnapshots, boolean playerMarked) {
        return new TemporalCommit(id, parentId, Type.SNAPSHOT, gameTime, Collections.emptyList(), chunkSnapshots, 0L, playerMarked);
    }

    /** A zero-diff marker commit created whenever a Time Machine checks out a point in time,
     * scoped to branchChunkPos so only that chunk's history forks here. */
    public static TemporalCommit branch(long id, long parentId, long gameTime, long branchChunkPos) {
        return new TemporalCommit(id, parentId, Type.BRANCH, gameTime, Collections.emptyList(), Collections.emptyList(), branchChunkPos, false);
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
    /** Whether a player's Portable ChronoMarker produced this commit; always false for Type.BRANCH. */
    public boolean isPlayerMarked() { return playerMarked; }

    public int getTotalChangeCount() {
        int count = 0;
        for (ChunkDelta cd : chunkDeltas) count += cd.getChangeCount();
        return count;
    }

    /** Deterministic 7-hex-digit label derived from this commit's identity, styled after a git short hash. */
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
        tag.putBoolean("PlayerMarked", playerMarked);

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
        boolean playerMarked = tag.getBoolean("PlayerMarked");

        ListTag chunkList = tag.getList("Chunks", Tag.TAG_COMPOUND);
        List<ChunkDelta> chunks = new ArrayList<>();
        for (int i = 0; i < chunkList.size(); i++) chunks.add(ChunkDelta.fromTag(chunkList.getCompound(i)));

        List<ChunkSnapshot> snapshots = new ArrayList<>();
        if (tag.contains("Snapshots", Tag.TAG_LIST)) {
            ListTag snapshotList = tag.getList("Snapshots", Tag.TAG_COMPOUND);
            for (int i = 0; i < snapshotList.size(); i++) snapshots.add(ChunkSnapshot.fromTag(snapshotList.getCompound(i)));
        }

        return new TemporalCommit(id, parentId, type, gameTime, chunks, snapshots, branchChunkPos, playerMarked);
    }

    public static void encode(TemporalCommit commit, FriendlyByteBuf buf) {
        buf.writeLong(commit.id);
        buf.writeLong(commit.parentId);
        buf.writeVarInt(commit.type.ordinal());
        buf.writeLong(commit.gameTime);
        buf.writeLong(commit.branchChunkPos);
        buf.writeBoolean(commit.playerMarked);

        buf.writeVarInt(commit.chunkDeltas.size());
        for (ChunkDelta cd : commit.chunkDeltas) ChunkDelta.encode(cd, buf);
    }

    public static TemporalCommit decode(FriendlyByteBuf buf) {
        long id = buf.readLong();
        long parentId = buf.readLong();
        Type type = Type.values()[buf.readVarInt()];
        long gameTime = buf.readLong();
        long branchChunkPos = buf.readLong();
        boolean playerMarked = buf.readBoolean();

        int chunkCount = buf.readVarInt();
        List<ChunkDelta> chunks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) chunks.add(ChunkDelta.decode(buf));

        return new TemporalCommit(id, parentId, type, gameTime, chunks, Collections.emptyList(), branchChunkPos, playerMarked);
    }

    // Shared chunk-scoped graph traversal, used identically by the server (to resolve/apply a
    // rollback) and the client (to preview one).

    /** The commit in candidates whose gameTime is nearest targetGameTime, or -1 if empty. On an
     * exact tie, prefers the later candidate, since a branch marker shares its parent's gameTime
     * and always appears after it. */
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
     * following localParentById links. Stops at the nearest SNAPSHOT ancestor rather than the true
     * root, since a snapshot is a full baseline and nothing before it affects the diff. */
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
