package io.github.tofithepuppycat.temporalindustries.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps the most recently resolved versions of another store so repeated visits skip the decode. */
public final class CachingStore implements ChunkHistoryStore {
    private final ChunkHistoryStore delegate;
    private final Map<Integer, int[]> cache;

    public CachingStore(ChunkHistoryStore delegate, int capacity) {
        this.delegate = delegate;
        this.cache = new LinkedHashMap<>(capacity * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, int[]> eldest) {
                return size() > capacity;
            }
        };
    }

    @Override public String name() { return delegate.name() + " + LRU cache"; }

    @Override public int commit(int[] volume) { return delegate.commit(volume); }

    @Override public int[] resolve(int version) {
        return cache.computeIfAbsent(version, delegate::resolve);
    }

    @Override public ChunkDiff diff(int from, int to) { return delegate.diff(from, to); }

    @Override public int versionCount() { return delegate.versionCount(); }

    @Override public byte[] serialize() { return delegate.serialize(); }
}
