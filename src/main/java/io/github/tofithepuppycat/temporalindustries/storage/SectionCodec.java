package io.github.tofithepuppycat.temporalindustries.storage;

import java.util.Arrays;

import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTION_SIZE;

/**
 * Byte encodings of one 4096-block section: uniform, palette + run-length (the layout the mod's
 * snapshots use), or palette + bit-packed indices.
 */
final class SectionCodec {
    private static final int TAG_UNIFORM = 0;
    private static final int TAG_RLE = 1;
    private static final int TAG_PACKED = 2;

    private SectionCodec() {}

    private static boolean isUniform(int[] volume, int off) {
        int first = volume[off];
        for (int i = 1; i < SECTION_SIZE; i++) {
            if (volume[off + i] != first) return false;
        }
        return true;
    }

    private static byte[] encodeUniform(int block) {
        ByteSink sink = new ByteSink();
        sink.writeByte(TAG_UNIFORM);
        sink.writeVarInt(block);
        return sink.toByteArray();
    }

    /** Palette in first-seen order; fills indices with each block's palette slot. */
    private static int[] buildPalette(int[] volume, int off, int[] indices) {
        int[] keys = new int[8192];
        int[] slots = new int[8192];
        Arrays.fill(keys, -1);
        int[] palette = new int[SECTION_SIZE];
        int count = 0;
        for (int i = 0; i < SECTION_SIZE; i++) {
            int block = volume[off + i];
            int h = (block * 0x9E3779B1) >>> 19;
            while (keys[h] != -1 && keys[h] != block) h = (h + 1) & 8191;
            if (keys[h] == -1) {
                keys[h] = block;
                slots[h] = count;
                palette[count++] = block;
            }
            indices[i] = slots[h];
        }
        return Arrays.copyOf(palette, count);
    }

    static byte[] encodeRle(int[] volume, int off) {
        if (isUniform(volume, off)) return encodeUniform(volume[off]);
        int[] indices = new int[SECTION_SIZE];
        int[] palette = buildPalette(volume, off, indices);
        return rle(palette, indices);
    }

    private static byte[] rle(int[] palette, int[] indices) {
        ByteSink sink = new ByteSink();
        sink.writeByte(TAG_RLE);
        sink.writeVarInt(palette.length);
        for (int block : palette) sink.writeVarInt(block);
        int runs = 1;
        for (int i = 1; i < SECTION_SIZE; i++) if (indices[i] != indices[i - 1]) runs++;
        sink.writeVarInt(runs);
        int start = 0;
        for (int i = 1; i <= SECTION_SIZE; i++) {
            if (i == SECTION_SIZE || indices[i] != indices[start]) {
                sink.writeVarInt(indices[start]);
                sink.writeVarInt(i - start);
                start = i;
            }
        }
        return sink.toByteArray();
    }

    private static int bitsFor(int paletteSize) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    private static byte[] packed(int[] palette, int[] indices) {
        ByteSink sink = new ByteSink();
        sink.writeByte(TAG_PACKED);
        sink.writeVarInt(palette.length);
        for (int block : palette) sink.writeVarInt(block);
        int bits = bitsFor(palette.length);
        long acc = 0;
        int accBits = 0;
        for (int i = 0; i < SECTION_SIZE; i++) {
            acc |= (long) indices[i] << accBits;
            accBits += bits;
            while (accBits >= 8) {
                sink.writeByte((int) (acc & 0xFF));
                acc >>>= 8;
                accBits -= 8;
            }
        }
        if (accBits > 0) sink.writeByte((int) (acc & 0xFF));
        return sink.toByteArray();
    }

    /** The smaller of the run-length and bit-packed encodings. */
    static byte[] encodeAdaptive(int[] volume, int off) {
        if (isUniform(volume, off)) return encodeUniform(volume[off]);
        int[] indices = new int[SECTION_SIZE];
        int[] palette = buildPalette(volume, off, indices);
        byte[] a = rle(palette, indices);
        byte[] b = packed(palette, indices);
        return a.length <= b.length ? a : b;
    }

    static void decode(byte[] encoded, int[] out, int off) {
        ByteSource src = new ByteSource(encoded);
        int tag = src.readByte();
        if (tag == TAG_UNIFORM) {
            Arrays.fill(out, off, off + SECTION_SIZE, src.readVarInt());
            return;
        }
        int[] palette = new int[src.readVarInt()];
        for (int i = 0; i < palette.length; i++) palette[i] = src.readVarInt();
        if (tag == TAG_RLE) {
            int runs = src.readVarInt();
            int pos = off;
            for (int r = 0; r < runs; r++) {
                int block = palette[src.readVarInt()];
                int len = src.readVarInt();
                Arrays.fill(out, pos, pos + len, block);
                pos += len;
            }
            return;
        }
        int bits = bitsFor(palette.length);
        int mask = (1 << bits) - 1;
        long acc = 0;
        int accBits = 0;
        for (int i = 0; i < SECTION_SIZE; i++) {
            while (accBits < bits) {
                acc |= (long) src.readByte() << accBits;
                accBits += 8;
            }
            out[off + i] = palette[(int) (acc & mask)];
            acc >>>= bits;
            accBits -= bits;
        }
    }
}
