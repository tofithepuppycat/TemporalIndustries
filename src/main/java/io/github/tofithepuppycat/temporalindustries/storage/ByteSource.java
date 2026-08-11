package io.github.tofithepuppycat.temporalindustries.storage;

/** Sequential reader matching {@link ByteSink}. */
final class ByteSource {
    private final byte[] data;
    private int pos;

    ByteSource(byte[] data) { this.data = data; }

    int readByte() { return data[pos++] & 0xFF; }

    int readVarInt() {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = data[pos++];
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }
}
