package io.github.tofithepuppycat.temporalindustries.network;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Raw DEFLATE compression for packet payloads (timeline NBT/commit data is highly repetitive
 * and compresses well). No zlib/gzip header — just the compressed stream, since the length is
 * already tracked by the surrounding FriendlyByteBuf#writeByteArray. */
final class CompressionUtil {
    private CompressionUtil() {}

    static byte[] compress(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, input.length / 2));
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            out.write(buf, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }

    static byte[] decompress(byte[] input) {
        Inflater inflater = new Inflater(true);
        inflater.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, input.length * 2));
        byte[] buf = new byte[4096];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                if (count == 0 && inflater.needsInput()) break;
                out.write(buf, 0, count);
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("Corrupt compressed packet payload", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
