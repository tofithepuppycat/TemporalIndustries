package io.github.tofithepuppycat.temporalindustries.timeline;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A full-chunk baseline: every block in the chunk, not just what changed (contrast {@link
 * BlockChangeDelta}).
 */
public final class ChunkSnapshot {

    /**
     * Bottom-up (y, then z, then x) order; used so rollback restores blocks deterministically
     * (support before dependent) instead of in arbitrary HashMap iteration order.
     */
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

    public int sectionCount() { return sections.size(); }
    public int minSectionY() { return minSectionY; }

    /**
     * Whether section sectionIndex is the same single uniform state in both this snapshot and
     * other's, letting a whole section be recognized as identical in O(1).
     */
    public boolean sectionTriviallyEquals(ChunkSnapshot other, int sectionIndex) {
        SnapshotSection a = sections.get(sectionIndex);
        SnapshotSection b = other.sections.get(sectionIndex);
        return a.uniform && b.uniform && a.uniformState.equals(b.uniformState);
    }

    /** Whether section sectionIndex holds exactly the same blocks in this snapshot and other's. */
    public boolean sectionSameBlocks(ChunkSnapshot other, int sectionIndex) {
        return sections.get(sectionIndex).sameBlocks(other.sections.get(sectionIndex));
    }

    /** The block state at pos, which must lie inside this snapshot's chunk and height range. */
    public BlockState stateAt(BlockPos pos) {
        int sectionIndex = (pos.getY() >> 4) - minSectionY;
        return sections.get(sectionIndex).stateAt(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    /** Collects just section sectionIndex's positions, without expanding every other section. */
    public void collectSectionInto(int sectionIndex, Map<BlockPos, BlockState> out) {
        sections.get(sectionIndex).collectInto(chunkPos, minSectionY + sectionIndex, out);
    }

    /**
     * Adds to out, bottom-up, every position of section sectionIndex whose block in live differs
     * from the one captured here; unchanged blocks allocate nothing.
     */
    public void collectDifferingInto(int sectionIndex, LevelChunkSection live, List<BlockPos> out) {
        sections.get(sectionIndex).collectDiffering(chunkPos, minSectionY + sectionIndex, live, out);
    }

    /** Section sectionIndex's blocks as a flat array indexed (y * 16 + z) * 16 + x. */
    public BlockState[] decodeSection(int sectionIndex) {
        BlockState[] out = new BlockState[16 * 16 * 16];
        sections.get(sectionIndex).decode(out);
        return out;
    }

    /** Every block position this baseline covers, expanded from its section encoding. */
    public Map<BlockPos, BlockState> toBlockStateMap() {
        Map<BlockPos, BlockState> map = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            sections.get(i).collectInto(chunkPos, minSectionY + i, map);
        }
        return map;
    }

    /** This baseline's captured block entity NBT, keyed by position. */
    public Map<BlockPos, CompoundTag> getBlockEntityTags() {
        return blockEntities;
    }

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

    // NBT — server-only; never sent over the network.

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

    // One 16x16x16 section: either a single uniform state, or a palette + run-length encoding.

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

            Reference2IntOpenHashMap<BlockState> paletteIndex = new Reference2IntOpenHashMap<>();
            paletteIndex.defaultReturnValue(-1);
            List<BlockState> palette = new ArrayList<>();
            IntArrayList runPaletteIndices = new IntArrayList();
            IntArrayList runLengths = new IntArrayList();
            BlockState previous = null;
            int previousIndex = -1;
            int runLength = 0;

            // y-major, then z, then x: sections are usually more uniform within one Y-layer than
            // across the vertical stack, maximizing run length. Block states are interned, so a
            // run is extended by identity and the palette is only consulted when it ends.
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state == previous) {
                            runLength++;
                            continue;
                        }
                        if (previous != null) {
                            runPaletteIndices.add(previousIndex);
                            runLengths.add(runLength);
                        }
                        int index = paletteIndex.getInt(state);
                        if (index < 0) {
                            index = palette.size();
                            palette.add(state);
                            paletteIndex.put(state, index);
                        }
                        previous = state;
                        previousIndex = index;
                        runLength = 1;
                    }
                }
            }
            runPaletteIndices.add(previousIndex);
            runLengths.add(runLength);

            if (runPaletteIndices.size() == 1) {
                return new SnapshotSection(palette.get(0));
            }
            return new SnapshotSection(palette, runPaletteIndices.toIntArray(), runLengths.toIntArray());
        }

        /** Writes every block of this section into out, indexed (y * 16 + z) * 16 + x. */
        void decode(BlockState[] out) {
            if (uniform) {
                Arrays.fill(out, uniformState);
                return;
            }
            int cursor = 0;
            for (int run = 0; run < runPaletteIndex.length; run++) {
                Arrays.fill(out, cursor, cursor + runLength[run], palette.get(runPaletteIndex[run]));
                cursor += runLength[run];
            }
        }

        /**
         * Whether both sections hold the same blocks. Capturing is deterministic (palette entries
         * are numbered by first appearance in a fixed scan order), so equal contents encode
         * identically.
         */
        boolean sameBlocks(SnapshotSection other) {
            if (uniform || other.uniform) {
                return uniform && other.uniform && uniformState.equals(other.uniformState);
            }
            return palette.equals(other.palette)
                    && Arrays.equals(runPaletteIndex, other.runPaletteIndex)
                    && Arrays.equals(runLength, other.runLength);
        }

        BlockState stateAt(int x, int y, int z) {
            if (uniform) return uniformState;
            int target = (y * 16 + z) * 16 + x;
            int start = 0;
            for (int run = 0; run < runPaletteIndex.length; run++) {
                start += runLength[run];
                if (target < start) return palette.get(runPaletteIndex[run]);
            }
            return Blocks.AIR.defaultBlockState();
        }

        /**
         * Writes this section's captured state into a map, keyed by absolute position; explicit
         * about air rather than skipping it.
         */
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

        void collectDiffering(ChunkPos chunkPos, int sectionY, LevelChunkSection live, List<BlockPos> out) {
            int baseX = chunkPos.getMinBlockX();
            int baseZ = chunkPos.getMinBlockZ();
            int baseY = sectionY * 16;

            if (uniform) {
                if (uniformState.is(Blocks.AIR) && live.hasOnlyAir()) return;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!live.getBlockState(x, y, z).equals(uniformState)) {
                                out.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                            }
                        }
                    }
                }
                return;
            }

            int y = 0, z = 0, x = 0;
            for (int run = 0; run < runPaletteIndex.length; run++) {
                BlockState state = palette.get(runPaletteIndex[run]);
                for (int remaining = runLength[run]; remaining > 0; remaining--) {
                    if (!live.getBlockState(x, y, z).equals(state)) {
                        out.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                    }
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
