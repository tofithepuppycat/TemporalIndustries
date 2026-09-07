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
 * A full-chunk baseline: every block in the chunk, not just what changed (contrast
 * {@link BlockChangeDelta}). Backs {@link TemporalCommit.Type#SNAPSHOT} commits so a history walk
 * never has to go back further than the nearest one.
 *
 * <p>Each 16-cube section that's uniform (stone/air/bedrock) collapses to a single stored state;
 * non-uniform sections fall back to a palette + run-length encoding, scanned y-major/z/x to
 * maximize run length against typically Y/Z-layered terrain.
 */
public final class ChunkSnapshot {
    private static final int SECTION_VOLUME = 16 * 16 * 16;

    /** Bottom-up (y, then z, then x) order; used so rollback restores blocks deterministically
     * (support before dependent) instead of in arbitrary HashMap iteration order. */
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

    /** Whether section sectionIndex is the same single uniform state in both this snapshot and
     * other's, letting a whole section be recognized as identical in O(1). */
    public boolean sectionTriviallyEquals(ChunkSnapshot other, int sectionIndex) {
        SnapshotSection a = sections.get(sectionIndex);
        SnapshotSection b = other.sections.get(sectionIndex);
        return a.uniform && b.uniform && a.uniformState.equals(b.uniformState);
    }

    /** Collects just section sectionIndex's positions, without expanding every other section. */
    public void collectSectionInto(int sectionIndex, Map<BlockPos, BlockState> out) {
        sections.get(sectionIndex).collectInto(chunkPos, minSectionY + sectionIndex, out);
    }

    /** Every block position this baseline covers, expanded from its section encoding. Read-only;
     * unlike {@link #applyTo}, never touches the world. */
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

            BlockState first = section.getBlockState(0, 0, 0);
            boolean allSame = true;

            Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
            List<Integer> runPaletteIndices = new ArrayList<>();
            List<Integer> runLengths = new ArrayList<>();
            int currentIndex = -1;
            int currentRunLength = 0;

            // y-major, then z, then x: sections are usually more uniform within one Y-layer than
            // across the vertical stack, maximizing run length.
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

        /** Writes this section's captured state into a map, keyed by absolute position; explicit
         * about air rather than skipping it. */
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
