package io.github.tofithepuppycat.temporalindustries.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTION_SIZE;
import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTIONS;

/**
 * Newest-first storage: the latest version is kept whole and every older one is reached through
 * reverse deltas holding only the overwritten block. The head costs nothing to resolve or commit
 * against, and rolling back stays cheap because the common target is recent history.
 */
public final class ReverseDeltaStore implements ChunkHistoryStore {
    /** Changes between version k and k+1: the positions and their blocks as of version k. */
    private record Reverse(int[] indices, int[] blocks) {}

    private final List<Reverse> reverses = new ArrayList<>();
    private int[] head;

    @Override public String name() { return "Reverse delta (head materialised)"; }

    @Override
    public int commit(int[] volume) {
        if (head != null) {
            ChunkVolume.IntList indices = new ChunkVolume.IntList();
            ChunkVolume.IntList blocks = new ChunkVolume.IntList();
            for (int i = 0; i < ChunkVolume.VOLUME; i++) {
                if (head[i] != volume[i]) {
                    indices.add(i);
                    blocks.add(head[i]);
                }
            }
            reverses.add(new Reverse(indices.toArray(), blocks.toArray()));
        }
        head = volume.clone();
        return reverses.size();
    }

    @Override
    public int[] resolve(int version) {
        int[] out = head.clone();
        for (int k = reverses.size() - 1; k >= version; k--) {
            Reverse r = reverses.get(k);
            for (int i = 0; i < r.indices().length; i++) out[r.indices()[i]] = r.blocks()[i];
        }
        return out;
    }

    @Override
    public ChunkDiff diff(int from, int to) {
        if (from == to) return new ChunkDiff(new int[0], new int[0]);
        if (from > to) {
            Map<Integer, Integer> target = new HashMap<>();
            for (int k = from - 1; k >= to; k--) {
                Reverse r = reverses.get(k);
                for (int i = 0; i < r.indices().length; i++) target.put(r.indices()[i], r.blocks()[i]);
            }
            return toDiff(target);
        }
        int[] atTarget = resolve(to);
        Map<Integer, Integer> target = new HashMap<>();
        for (int k = from; k < to; k++) {
            for (int index : reverses.get(k).indices()) target.put(index, atTarget[index]);
        }
        return toDiff(target);
    }

    private static ChunkDiff toDiff(Map<Integer, Integer> target) {
        int[] indices = new int[target.size()];
        int[] blocks = new int[target.size()];
        int n = 0;
        for (Map.Entry<Integer, Integer> e : target.entrySet()) {
            indices[n] = e.getKey();
            blocks[n++] = e.getValue();
        }
        return new ChunkDiff(indices, blocks);
    }

    @Override public int versionCount() { return head == null ? 0 : reverses.size() + 1; }

    @Override
    public byte[] serialize() {
        ByteSink sink = new ByteSink();
        for (int s = 0; s < SECTIONS; s++) {
            byte[] section = SectionCodec.encodeAdaptive(head, s * SECTION_SIZE);
            sink.writeVarInt(section.length);
            sink.writeBytes(section);
        }
        sink.writeVarInt(reverses.size());
        for (Reverse r : reverses) {
            sink.writeVarInt(r.indices().length);
            int last = 0;
            for (int i = 0; i < r.indices().length; i++) {
                sink.writeVarInt(r.indices()[i] - last);
                sink.writeVarInt(r.blocks()[i]);
                last = r.indices()[i];
            }
        }
        return sink.toByteArray();
    }
}
