package io.github.tofithepuppycat.temporalindustries.storage;

import java.util.Arrays;

/** Growable byte buffer with LEB128 varint support. */
final class ByteSink {
    private byte[] data = new byte[64];
    private int size;

    void writeByte(int value) {
        if (size == data.length) data = Arrays.copyOf(data, size * 2);
        data[size++] = (byte) value;
    }

    void writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        writeByte(value);
    }

    void writeBytes(byte[] bytes) {
        if (size + bytes.length > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + bytes.length));
        System.arraycopy(bytes, 0, data, size, bytes.length);
        size += bytes.length;
    }

    byte[] toByteArray() { return Arrays.copyOf(data, size); }
}
