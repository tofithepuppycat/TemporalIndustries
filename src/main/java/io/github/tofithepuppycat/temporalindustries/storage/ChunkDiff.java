package io.github.tofithepuppycat.temporalindustries.storage;

/** The positions that must be rewritten to move a chunk between two versions, and their target blocks. */
public record ChunkDiff(int[] indices, int[] blocks) {
    public int size() { return indices.length; }
}
