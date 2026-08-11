package io.github.tofithepuppycat.temporalindustries.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTION_SIZE;
import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTIONS;

/**
 * Git-style object model: each section is an immutable blob interned in a content-addressed pool, and
 * a version is just the list of its 24 blob references. Unchanged sections cost nothing to commit,
 * store or compare, and any version resolves without replaying history.
 */
public final class SectionDedupStore implements ChunkHistoryStore {
    private final List<byte[]> pool = new ArrayList<>();
    private final Map<ByteBuffer, Integer> poolIndex = new HashMap<>();
    private final List<int[]> versions = new ArrayList<>();
    private int[] head;

    @Override public String name() { return "Section dedup (content-addressed)"; }

    @Override
    public int commit(int[] volume) {
        int[] previousRefs = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        int[] refs = new int[SECTIONS];
        for (int s = 0; s < SECTIONS; s++) {
            int off = s * SECTION_SIZE;
            if (previousRefs != null && Arrays.equals(volume, off, off + SECTION_SIZE, head, off, off + SECTION_SIZE)) {
                refs[s] = previousRefs[s];
            } else {
                refs[s] = intern(SectionCodec.encodeAdaptive(volume, off));
            }
        }
        versions.add(refs);
        head = volume.clone();
        return versions.size() - 1;
    }

    private int intern(byte[] blob) {
        return poolIndex.computeIfAbsent(ByteBuffer.wrap(blob), key -> {
            pool.add(blob);
            return pool.size() - 1;
        });
    }

    @Override
    public int[] resolve(int version) {
        int[] refs = versions.get(version);
        int[] out = new int[ChunkVolume.VOLUME];
        for (int s = 0; s < SECTIONS; s++) SectionCodec.decode(pool.get(refs[s]), out, s * SECTION_SIZE);
        return out;
    }

    @Override
    public ChunkDiff diff(int from, int to) {
        int[] a = versions.get(from);
        int[] b = versions.get(to);
        ChunkVolume.IntList indices = new ChunkVolume.IntList();
        ChunkVolume.IntList blocks = new ChunkVolume.IntList();
        int[] before = new int[SECTION_SIZE];
        int[] after = new int[SECTION_SIZE];
        for (int s = 0; s < SECTIONS; s++) {
            if (a[s] == b[s]) continue;
            SectionCodec.decode(pool.get(a[s]), before, 0);
            SectionCodec.decode(pool.get(b[s]), after, 0);
            for (int i = 0; i < SECTION_SIZE; i++) {
                if (before[i] != after[i]) {
                    indices.add(s * SECTION_SIZE + i);
                    blocks.add(after[i]);
                }
            }
        }
        return new ChunkDiff(indices.toArray(), blocks.toArray());
    }

    @Override public int versionCount() { return versions.size(); }

    @Override
    public byte[] serialize() {
        ByteSink sink = new ByteSink();
        sink.writeVarInt(pool.size());
        for (byte[] blob : pool) {
            sink.writeVarInt(blob.length);
            sink.writeBytes(blob);
        }
        sink.writeVarInt(versions.size());
        int[] previous = null;
        for (int[] refs : versions) {
            int mask = 0;
            for (int s = 0; s < SECTIONS; s++) {
                if (previous == null || previous[s] != refs[s]) mask |= 1 << s;
            }
            sink.writeByte(mask);
            sink.writeByte(mask >>> 8);
            sink.writeByte(mask >>> 16);
            for (int s = 0; s < SECTIONS; s++) {
                if ((mask & (1 << s)) != 0) sink.writeVarInt(refs[s]);
            }
            previous = refs;
        }
        return sink.toByteArray();
    }
}
