package io.github.tofithepuppycat.temporalindustries.timeline;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A full-chunk baseline: every block in the chunk, not just what's changed since some earlier
 * point (contrast {@link BlockChangeDelta}, which is deliberately sparse). Backs
 * {@link TemporalCommit.Type#SNAPSHOT} commits, which exist so {@link TemporalCommit#ancestryChain}
 * never has to walk further back than the nearest one — without a baseline to stop at, a chunk's
 * history walk (and the client's identical copy of that walk, for the ghost preview) grows without
 * bound for the life of the world.
 *
 * <p>Sixteen-cube sections are the natural unit of sparseness here — a section that's uniformly
 * stone, air, or bedrock (the overwhelming majority of any real chunk) collapses to a single
 * stored state instead of 4096 individual ones, the same win a sparse octree gets from a uniform
 * octant. Sections that aren't uniform (the thin, block-heterogeneous band a player actually builds
 * in) fall back to a palette + run-length encoding: real terrain is strongly Y/Z-layered, so a
 * scan in y-major, then z, then x order tends to produce long runs even where individual blocks
 * differ across a section.
 */
public final class ChunkSnapshot {
    private static final int SECTION_VOLUME = 16 * 16 * 16;

    /** Bottom-up (y, then z, then x) — the same traversal order {@link SnapshotSection} uses to
     * capture a chunk. Shared with {@link TemporalTimeline#applyChunkAtTime} so a delta-based
     * rollback restores blocks in a deterministic, support-before-dependent order instead of
     * whatever order a HashMap happens to iterate in — a plain HashMap order let gravity blocks
     * (sand/gravel) and neighbor-reactive blocks (attached torches, redstone) reach the world in
     * arbitrary order mid-restore. */
    public static final Comparator<BlockPos> BOTTOM_UP_ORDER =
            Comparator.<BlockPos>comparingInt(pos -> pos.getY())
                    .thenComparingInt(pos -> pos.getZ())
                    .thenComparingInt(pos -> pos.getX());

    private final ChunkPos chunkPos;
    private final int minSectionY;
    private final List<SnapshotSection> sections;
    private final Map<BlockPos, CompoundTag> blockEntities;

    private ChunkSnapshot(ChunkPos chunkPos, int minSectionY, List<SnapshotSection> sections, Map<BlockPos, CompoundTag> blockEntities) {
        this.chunkPos = chunkPos;
        this.minSectionY = minSectionY;
        this.sections = sections;
        this.blockEntities = blockEntities;
    }

    public ChunkPos getChunkPos() { return chunkPos; }

    /** Every block position this baseline covers, expanded from its section encoding — used to
     * seed a rollback's desired state when the chain being resolved has no shared history with the
     * live chain to diff deltas against (see TemporalTimeline#resolveDesiredState). Read-only: unlike
     * {@link #applyTo}, this never touches the world. */
    public Map<BlockPos, BlockState> toBlockStateMap() {
        Map<BlockPos, BlockState> map = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            sections.get(i).collectInto(chunkPos, minSectionY + i, map);
        }
        return map;
    }

    /** This baseline's captured block entity NBT, keyed by position — the read-only counterpart to
     * {@link #toBlockStateMap()} for {@link #applyTo}'s block entity restoration. */
    public Map<BlockPos, CompoundTag> getBlockEntityTags() {
        return blockEntities;
    }

    // -------------------------------------------------------------------------
    // Capture

    public static ChunkSnapshot capture(ServerLevel level, ChunkPos chunkPos) {
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        LevelChunkSection[] chunkSections = chunk.getSections();

        List<SnapshotSection> sections = new ArrayList<>(chunkSections.length);
        for (LevelChunkSection section : chunkSections) {
            sections.add(SnapshotSection.capture(section));
        }

        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            blockEntities.put(entry.getKey().immutable(), entry.getValue().saveWithFullMetadata(level.registryAccess()));
        }

        return new ChunkSnapshot(chunkPos, level.getMinSection(), sections, blockEntities);
    }

    // -------------------------------------------------------------------------
    // NBT — server-only; never sent over the network (clients already have the real blocks
    // synced through normal chunk data, so there's nothing for them to do with a full baseline).

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ChunkX", chunkPos.x);
        tag.putInt("ChunkZ", chunkPos.z);
        tag.putInt("MinSectionY", minSectionY);

        ListTag sectionList = new ListTag();
        for (SnapshotSection section : sections) sectionList.add(section.toTag());
        tag.put("Sections", sectionList);

        ListTag beList = new ListTag();
        for (Map.Entry<BlockPos, CompoundTag> entry : blockEntities.entrySet()) {
            CompoundTag beTag = new CompoundTag();
            beTag.putInt("x", entry.getKey().getX());
            beTag.putInt("y", entry.getKey().getY());
            beTag.putInt("z", entry.getKey().getZ());
            beTag.put("Data", entry.getValue());
            beList.add(beTag);
        }
        tag.put("BlockEntities", beList);

        return tag;
    }

    public static ChunkSnapshot fromTag(CompoundTag tag) {
        ChunkPos chunkPos = new ChunkPos(tag.getInt("ChunkX"), tag.getInt("ChunkZ"));
        int minSectionY = tag.getInt("MinSectionY");

        ListTag sectionList = tag.getList("Sections", Tag.TAG_COMPOUND);
        List<SnapshotSection> sections = new ArrayList<>(sectionList.size());
        for (int i = 0; i < sectionList.size(); i++) sections.add(SnapshotSection.fromTag(sectionList.getCompound(i)));

        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        ListTag beList = tag.getList("BlockEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < beList.size(); i++) {
            CompoundTag beTag = beList.getCompound(i);
            BlockPos pos = new BlockPos(beTag.getInt("x"), beTag.getInt("y"), beTag.getInt("z"));
            blockEntities.put(pos, beTag.getCompound("Data"));
        }

        return new ChunkSnapshot(chunkPos, minSectionY, sections, blockEntities);
    }

    // -------------------------------------------------------------------------
    // One 16x16x16 section: either a single uniform state (the common case for a real chunk —
    // deep stone, sky air, bedrock), or a palette + run-length encoding of all 4096 positions.

    private static final class SnapshotSection {
        private final boolean uniform;
        private final BlockState uniformState;
        private final List<BlockState> palette;
        private final int[] runPaletteIndex;
        private final int[] runLength;

        private SnapshotSection(BlockState uniformState) {
            this.uniform = true;
            this.uniformState = uniformState;
            this.palette = null;
            this.runPaletteIndex = null;
            this.runLength = null;
        }

        private SnapshotSection(List<BlockState> palette, int[] runPaletteIndex, int[] runLength) {
            this.uniform = false;
            this.uniformState = null;
            this.palette = palette;
            this.runPaletteIndex = runPaletteIndex;
            this.runLength = runLength;
        }

        static SnapshotSection capture(LevelChunkSection section) {
            if (section.hasOnlyAir()) {
                return new SnapshotSection(Blocks.AIR.defaultBlockState());
            }

            BlockState first = section.getBlockState(0, 0, 0);
            boolean allSame = true;

            Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
            List<Integer> runPaletteIndices = new ArrayList<>();
            List<Integer> runLengths = new ArrayList<>();
            int currentIndex = -1;
            int currentRunLength = 0;

            // y-major, then z, then x: a section is usually far more uniform within one Y-layer
            // (a slab of the same stone/ore/etc.) than across the vertical stack a player has
            // actually dug through or built on, so this order maximises run length in practice.
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (allSame && !state.equals(first)) allSame = false;

                        int index = paletteIndex.computeIfAbsent(state, s -> paletteIndex.size());
                        if (index == currentIndex) {
                            currentRunLength++;
                        } else {
                            if (currentRunLength > 0) {
                                runPaletteIndices.add(currentIndex);
                                runLengths.add(currentRunLength);
                            }
                            currentIndex = index;
                            currentRunLength = 1;
                        }
                    }
                }
            }
            if (currentRunLength > 0) {
                runPaletteIndices.add(currentIndex);
                runLengths.add(currentRunLength);
            }

            if (allSame) {
                return new SnapshotSection(first);
            }

            List<BlockState> palette = new ArrayList<>(paletteIndex.keySet());
            int[] indices = runPaletteIndices.stream().mapToInt(Integer::intValue).toArray();
            int[] lengths = runLengths.stream().mapToInt(Integer::intValue).toArray();
            return new SnapshotSection(palette, indices, lengths);
        }

        /** Writes this section's captured state into a map, keyed by absolute position — explicit
         * about air rather than skipping it, so a caller can tell "this position should be cleared"
         * from "this position isn't covered". */
        void collectInto(ChunkPos chunkPos, int sectionY, Map<BlockPos, BlockState> out) {
            int baseX = chunkPos.getMinBlockX();
            int baseZ = chunkPos.getMinBlockZ();
            int baseY = sectionY * 16;

            if (uniform) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            out.put(new BlockPos(baseX + x, baseY + y, baseZ + z), uniformState);
                        }
                    }
                }
                return;
            }

            int y = 0, z = 0, x = 0;
            for (int run = 0; run < runPaletteIndex.length; run++) {
                BlockState state = palette.get(runPaletteIndex[run]);
                int remaining = runLength[run];
                while (remaining > 0) {
                    out.put(new BlockPos(baseX + x, baseY + y, baseZ + z), state);
                    remaining--;
                    x++;
                    if (x == 16) { x = 0; z++; if (z == 16) { z = 0; y++; } }
                }
            }
        }

        CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("Uniform", uniform);
            if (uniform) {
                BlockState.CODEC.encodeStart(NbtOps.INSTANCE, uniformState).result().ifPresent(nbt -> tag.put("State", nbt));
                return tag;
            }

            ListTag paletteList = new ListTag();
            for (BlockState state : palette) {
                BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result().ifPresent(paletteList::add);
            }
            tag.put("Palette", paletteList);

            int[] interleaved = new int[runPaletteIndex.length * 2];
            for (int i = 0; i < runPaletteIndex.length; i++) {
                interleaved[i * 2] = runPaletteIndex[i];
                interleaved[i * 2 + 1] = runLength[i];
            }
            tag.putIntArray("Runs", interleaved);
            return tag;
        }

        static SnapshotSection fromTag(CompoundTag tag) {
            if (tag.getBoolean("Uniform")) {
                BlockState state = tag.contains("State")
                        ? BlockState.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("State")).result().orElse(Blocks.AIR.defaultBlockState())
                        : Blocks.AIR.defaultBlockState();
                return new SnapshotSection(state);
            }

            ListTag paletteList = tag.getList("Palette", Tag.TAG_COMPOUND);
            List<BlockState> palette = new ArrayList<>(paletteList.size());
            for (int i = 0; i < paletteList.size(); i++) {
                palette.add(BlockState.CODEC.parse(NbtOps.INSTANCE, paletteList.getCompound(i)).result().orElse(Blocks.AIR.defaultBlockState()));
            }

            int[] interleaved = tag.getIntArray("Runs");
            int runCount = interleaved.length / 2;
            int[] indices = new int[runCount];
            int[] lengths = new int[runCount];
            for (int i = 0; i < runCount; i++) {
                indices[i] = interleaved[i * 2];
                lengths[i] = interleaved[i * 2 + 1];
            }
            return new SnapshotSection(palette, indices, lengths);
        }
    }
}
