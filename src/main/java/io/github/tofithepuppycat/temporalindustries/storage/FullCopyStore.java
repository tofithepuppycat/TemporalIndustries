package io.github.tofithepuppycat.temporalindustries.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Reference strategy: every version is a complete, uncompressed copy of the chunk. */
public final class FullCopyStore implements ChunkHistoryStore {
    private final List<int[]> versions = new ArrayList<>();

    @Override public String name() { return "Naive full copy"; }

    @Override
    public int commit(int[] volume) {
        versions.add(volume.clone());
        return versions.size() - 1;
    }

    @Override public int[] resolve(int version) { return versions.get(version).clone(); }

    @Override
    public ChunkDiff diff(int from, int to) {
        return ChunkVolume.scan(versions.get(from), versions.get(to));
    }

    @Override public int versionCount() { return versions.size(); }

    @Override
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(versions.size() * ChunkVolume.VOLUME * Integer.BYTES);
        for (int[] version : versions) {
            for (int block : version) buf.putInt(block);
        }
        return buf.array();
    }
}
