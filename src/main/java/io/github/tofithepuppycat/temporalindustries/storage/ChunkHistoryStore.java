package io.github.tofithepuppycat.temporalindustries.storage;

/**
 * Version history of a single chunk column. Versions are numbered from 0 in commit order; every
 * strategy must reproduce each committed volume exactly.
 */
public interface ChunkHistoryStore {
    String name();

    /** Records volume as the next version and returns its number. The array is not retained. */
    int commit(int[] volume);

    /** The full contents of version. The result is read-only for caching stores. */
    int[] resolve(int version);

    /**
     * Writes needed to turn version from into version to. Strategies may include positions whose
     * block ends up unchanged, but never omit one that differs.
     */
    ChunkDiff diff(int from, int to);

    int versionCount();

    /** The persisted form of the whole history. */
    byte[] serialize();
}
