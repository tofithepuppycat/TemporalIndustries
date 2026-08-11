package io.github.tofithepuppycat.temporalindustries.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTION_SIZE;
import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTIONS;

/**
 * The strategy {@code TemporalRepository} uses today: a FULL entry of run-length encoded sections,
 * then forward DELTA entries holding (position, previous, new), cut with a fresh FULL once the chain
 * reaches {@link #MAX_DELTA_CHAIN}. Resolving replays the chain from the nearest FULL.
 */
public final class RleDeltaChainStore implements ChunkHistoryStore {
    static final int MAX_DELTA_CHAIN = 32;

    private record Entry(byte[][] full, int[] indices, int[] previous, int[] next) {
        boolean isFull() { return full != null; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int chainLength;

    @Override public String name() { return "Mod baseline (RLE + delta chain)"; }

    @Override
    public int commit(int[] volume) {
        if (entries.isEmpty()) {
            entries.add(fullEntry(volume));
        } else {
            int[] head = resolve(entries.size() - 1);
            ChunkVolume.IntList indices = new ChunkVolume.IntList();
            ChunkVolume.IntList previous = new ChunkVolume.IntList();
            ChunkVolume.IntList next = new ChunkVolume.IntList();
            for (int i = 0; i < ChunkVolume.VOLUME; i++) {
                if (head[i] != volume[i]) {
                    indices.add(i);
                    previous.add(head[i]);
                    next.add(volume[i]);
                }
            }
            if (chainLength >= MAX_DELTA_CHAIN && indices.size() > 0) {
                entries.add(fullEntry(volume));
                chainLength = 0;
            } else {
                entries.add(new Entry(null, indices.toArray(), previous.toArray(), next.toArray()));
                chainLength++;
            }
        }
        return entries.size() - 1;
    }

    private static Entry fullEntry(int[] volume) {
        byte[][] sections = new byte[SECTIONS][];
        for (int s = 0; s < SECTIONS; s++) sections[s] = SectionCodec.encodeRle(volume, s * SECTION_SIZE);
        return new Entry(sections, null, null, null);
    }

    @Override
    public int[] resolve(int version) {
        int base = version;
        while (!entries.get(base).isFull()) base--;
        int[] out = new int[ChunkVolume.VOLUME];
        byte[][] sections = entries.get(base).full();
        for (int s = 0; s < SECTIONS; s++) SectionCodec.decode(sections[s], out, s * SECTION_SIZE);
        for (int v = base + 1; v <= version; v++) {
            Entry delta = entries.get(v);
            for (int i = 0; i < delta.indices().length; i++) out[delta.indices()[i]] = delta.next()[i];
        }
        return out;
    }

    @Override
    public ChunkDiff diff(int from, int to) {
        if (from == to) return new ChunkDiff(new int[0], new int[0]);
        int lo = Math.min(from, to);
        int hi = Math.max(from, to);
        for (int v = lo + 1; v <= hi; v++) {
            if (entries.get(v).isFull()) return ChunkVolume.scan(resolve(from), resolve(to));
        }

        boolean forward = to > from;
        Map<Integer, Integer> start = new HashMap<>();
        Map<Integer, Integer> end = new HashMap<>();
        if (forward) {
            for (int v = from + 1; v <= to; v++) record(entries.get(v), true, start, end);
        } else {
            for (int v = from; v > to; v--) record(entries.get(v), false, start, end);
        }
        ChunkVolume.IntList indices = new ChunkVolume.IntList();
        ChunkVolume.IntList blocks = new ChunkVolume.IntList();
        for (Map.Entry<Integer, Integer> e : end.entrySet()) {
            if (!e.getValue().equals(start.get(e.getKey()))) {
                indices.add(e.getKey());
                blocks.add(e.getValue());
            }
        }
        return new ChunkDiff(indices.toArray(), blocks.toArray());
    }

    private static void record(Entry delta, boolean forward, Map<Integer, Integer> start, Map<Integer, Integer> end) {
        int[] first = forward ? delta.previous() : delta.next();
        int[] last = forward ? delta.next() : delta.previous();
        for (int i = 0; i < delta.indices().length; i++) {
            start.putIfAbsent(delta.indices()[i], first[i]);
            end.put(delta.indices()[i], last[i]);
        }
    }

    @Override public int versionCount() { return entries.size(); }

    @Override
    public byte[] serialize() {
        ByteSink sink = new ByteSink();
        sink.writeVarInt(entries.size());
        for (Entry entry : entries) {
            if (entry.isFull()) {
                sink.writeByte(1);
                for (byte[] section : entry.full()) {
                    sink.writeVarInt(section.length);
                    sink.writeBytes(section);
                }
            } else {
                sink.writeByte(0);
                sink.writeVarInt(entry.indices().length);
                for (int i = 0; i < entry.indices().length; i++) {
                    sink.writeVarInt(entry.indices()[i]);
                    sink.writeVarInt(entry.previous()[i]);
                    sink.writeVarInt(entry.next()[i]);
                }
            }
        }
        return sink.toByteArray();
    }
}
