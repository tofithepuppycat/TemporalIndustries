package io.github.tofithepuppycat.temporalindustries.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTION_SIZE;
import static io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume.SECTIONS;

/**
 * Per-section version chains. A version points at one node per section; a section that did not change
 * reuses its node, and a changed one gets either a small delta against its previous node or, when the
 * accumulated deltas would cost about as much as a snapshot, a fresh keyframe. Chains are therefore
 * bounded by bytes rather than by a fixed length, and no replay ever crosses a section boundary.
 */
public final class SectionDeltaStore implements ChunkHistoryStore {
    static final int MAX_CHAIN_DEPTH = 24;
    /** Approximate encoded size of one (position, block) delta entry. */
    private static final int DELTA_ENTRY_BYTES = 4;

    private static final class Node {
        final int parent;
        final int depth;
        final int chainBytes;
        final byte[] keyframe;
        final short[] positions;
        final int[] blocks;

        Node(byte[] keyframe) {
            this.parent = -1;
            this.depth = 0;
            this.chainBytes = keyframe.length;
            this.keyframe = keyframe;
            this.positions = null;
            this.blocks = null;
        }

        Node(Node parent, int parentId, short[] positions, int[] blocks) {
            this.parent = parentId;
            this.depth = parent.depth + 1;
            this.chainBytes = parent.chainBytes + positions.length * DELTA_ENTRY_BYTES;
            this.keyframe = null;
            this.positions = positions;
            this.blocks = blocks;
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    private final List<int[]> versions = new ArrayList<>();
    private int[] head;

    @Override public String name() { return "Section delta (cost-based keyframes)"; }

    @Override
    public int commit(int[] volume) {
        int[] previousRefs = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        int[] refs = new int[SECTIONS];
        for (int s = 0; s < SECTIONS; s++) {
            int off = s * SECTION_SIZE;
            if (previousRefs == null) {
                refs[s] = addKeyframe(volume, off);
            } else if (Arrays.equals(volume, off, off + SECTION_SIZE, head, off, off + SECTION_SIZE)) {
                refs[s] = previousRefs[s];
            } else {
                refs[s] = addChanged(previousRefs[s], volume, off);
            }
        }
        versions.add(refs);
        head = volume.clone();
        return versions.size() - 1;
    }

    private int addKeyframe(int[] volume, int off) {
        nodes.add(new Node(SectionCodec.encodeAdaptive(volume, off)));
        return nodes.size() - 1;
    }

    private int addChanged(int parentId, int[] volume, int off) {
        ChunkVolume.IntList positions = new ChunkVolume.IntList();
        ChunkVolume.IntList blocks = new ChunkVolume.IntList();
        for (int i = 0; i < SECTION_SIZE; i++) {
            if (volume[off + i] != head[off + i]) {
                positions.add(i);
                blocks.add(volume[off + i]);
            }
        }
        Node parent = nodes.get(parentId);
        byte[] keyframe = SectionCodec.encodeAdaptive(volume, off);
        int deltaCost = parent.chainBytes + positions.size() * DELTA_ENTRY_BYTES;
        if (parent.depth >= MAX_CHAIN_DEPTH || deltaCost > keyframe.length * 2) {
            nodes.add(new Node(keyframe));
        } else {
            short[] pos = new short[positions.size()];
            for (int i = 0; i < pos.length; i++) pos[i] = (short) positions.get(i);
            nodes.add(new Node(parent, parentId, pos, blocks.toArray()));
        }
        return nodes.size() - 1;
    }

    /** Decodes node's section into out[0..SECTION_SIZE). */
    private void resolveSection(int nodeId, int[] out) {
        int[] chain = new int[MAX_CHAIN_DEPTH + 1];
        int length = 0;
        int cur = nodeId;
        while (nodes.get(cur).keyframe == null) {
            chain[length++] = cur;
            cur = nodes.get(cur).parent;
        }
        SectionCodec.decode(nodes.get(cur).keyframe, out, 0);
        for (int i = length - 1; i >= 0; i--) {
            Node n = nodes.get(chain[i]);
            for (int j = 0; j < n.positions.length; j++) out[n.positions[j]] = n.blocks[j];
        }
    }

    @Override
    public int[] resolve(int version) {
        int[] refs = versions.get(version);
        int[] out = new int[ChunkVolume.VOLUME];
        int[] section = new int[SECTION_SIZE];
        for (int s = 0; s < SECTIONS; s++) {
            resolveSection(refs[s], section);
            System.arraycopy(section, 0, out, s * SECTION_SIZE, SECTION_SIZE);
        }
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
            resolveSection(a[s], before);
            resolveSection(b[s], after);
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
        sink.writeVarInt(nodes.size());
        for (Node n : nodes) {
            if (n.keyframe != null) {
                sink.writeByte(1);
                sink.writeVarInt(n.keyframe.length);
                sink.writeBytes(n.keyframe);
            } else {
                sink.writeByte(0);
                sink.writeVarInt(n.parent);
                sink.writeVarInt(n.positions.length);
                int last = 0;
                for (int i = 0; i < n.positions.length; i++) {
                    sink.writeVarInt(n.positions[i] - last);
                    sink.writeVarInt(n.blocks[i]);
                    last = n.positions[i];
                }
            }
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
