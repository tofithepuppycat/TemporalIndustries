package io.github.tofithepuppycat.temporalindustries.storage;

import java.util.Arrays;

/**
 * Layout of one chunk column as a flat block id array: 24 sections of 16x16x16, each stored y, then
 * z, then x, matching the order {@code ChunkSnapshot} encodes its runs in.
 */
public final class ChunkVolume {
    public static final int SECTION_SIZE = 16 * 16 * 16;
    public static final int SECTIONS = 24;
    public static final int HEIGHT = SECTIONS * 16;
    public static final int VOLUME = SECTION_SIZE * SECTIONS;

    private ChunkVolume() {}

    /** Flat index of the block at chunk-local x/z and column y (0..HEIGHT-1). */
    public static int index(int x, int y, int z) {
        return (y >> 4) * SECTION_SIZE + ((((y & 15) << 4) | z) << 4 | x);
    }

    /** Applies diff to volume in place. */
    public static void apply(int[] volume, ChunkDiff diff) {
        for (int i = 0; i < diff.size(); i++) volume[diff.indices()[i]] = diff.blocks()[i];
    }

    /** Every index where a and b differ, with b's block as the target. */
    public static ChunkDiff scan(int[] a, int[] b) {
        IntList indices = new IntList();
        IntList blocks = new IntList();
        for (int i = 0; i < VOLUME; i++) {
            if (a[i] != b[i]) {
                indices.add(i);
                blocks.add(b[i]);
            }
        }
        return new ChunkDiff(indices.toArray(), blocks.toArray());
    }

    /** Minimal growable int buffer. */
    public static final class IntList {
        private int[] data = new int[16];
        private int size;

        public void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = value;
        }

        public int size() { return size; }

        public int get(int i) { return data[i]; }

        public int[] toArray() { return Arrays.copyOf(data, size); }
    }
}
