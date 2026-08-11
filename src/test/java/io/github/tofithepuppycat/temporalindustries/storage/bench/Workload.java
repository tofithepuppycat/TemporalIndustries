package io.github.tofithepuppycat.temporalindustries.storage.bench;

import io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic synthetic chunk histories: a terrain column followed by one edit pattern per version. */
public enum Workload {
    SPARSE_EDITS("Sparse edits"),
    BUILDING("Building"),
    MACHINE_FARM("Machine farm"),
    EXPLOSIONS("Explosions"),
    MIXED("Mixed");

    static final int AIR = 0, STONE = 1, DIRT = 2, GRASS = 3, DEEPSLATE = 4, COAL = 5, IRON = 6, BEDROCK = 7;
    static final int BUILD_FIRST = 10, BUILD_COUNT = 24;
    static final int MACHINE_FIRST = 100, MACHINE_STATES = 16;
    /** World y 0 sits at column y 64. */
    static final int SEA_LEVEL = 64 + 62;

    public final String label;

    Workload(String label) { this.label = label; }

    /** versions volumes, the first being untouched terrain; each later one edits a copy of its predecessor. */
    public List<int[]> generate(int versions, long seed) {
        Random rng = new Random(seed);
        List<int[]> out = new ArrayList<>(versions);
        int[] current = terrain(rng);
        out.add(current.clone());
        for (int v = 1; v < versions; v++) {
            Workload pattern = this == MIXED ? values()[rng.nextInt(4)] : this;
            switch (pattern) {
                case SPARSE_EDITS -> sparseEdits(current, rng);
                case BUILDING -> build(current, rng);
                case MACHINE_FARM -> machines(current, rng);
                case EXPLOSIONS -> explode(current, rng);
                default -> throw new IllegalStateException();
            }
            out.add(current.clone());
        }
        return out;
    }

    private static int[] terrain(Random rng) {
        int[] vol = new int[ChunkVolume.VOLUME];
        double phase = rng.nextDouble() * 6.28;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int surface = SEA_LEVEL + (int) (6 * Math.sin(x * 0.35 + phase) + 4 * Math.cos(z * 0.5 + phase * 2)) + rng.nextInt(2);
                for (int y = 0; y <= surface; y++) {
                    int block;
                    if (y < 4) block = y == 0 ? BEDROCK : (rng.nextInt(4) < y ? DEEPSLATE : BEDROCK);
                    else if (y < 64) block = DEEPSLATE;
                    else if (y < surface - 3) block = STONE;
                    else if (y < surface) block = DIRT;
                    else block = GRASS;
                    if ((block == STONE || block == DEEPSLATE) && rng.nextInt(60) == 0) block = rng.nextBoolean() ? COAL : IRON;
                    vol[ChunkVolume.index(x, y, z)] = block;
                }
            }
        }
        for (int c = 0; c < 6; c++) carve(vol, rng.nextInt(16), 70 + rng.nextInt(50), rng.nextInt(16), 2 + rng.nextInt(3));
        return vol;
    }

    private static void carve(int[] vol, int cx, int cy, int cz, int r) {
        for (int x = Math.max(0, cx - r); x <= Math.min(15, cx + r); x++) {
            for (int z = Math.max(0, cz - r); z <= Math.min(15, cz + r); z++) {
                for (int y = Math.max(1, cy - r); y <= Math.min(ChunkVolume.HEIGHT - 1, cy + r); y++) {
                    int dx = x - cx, dy = y - cy, dz = z - cz;
                    if (dx * dx + dy * dy + dz * dz <= r * r) vol[ChunkVolume.index(x, y, z)] = AIR;
                }
            }
        }
    }

    private static void sparseEdits(int[] vol, Random rng) {
        int edits = 1 + rng.nextInt(20);
        for (int i = 0; i < edits; i++) {
            int x = rng.nextInt(16), z = rng.nextInt(16), y = SEA_LEVEL - 8 + rng.nextInt(20);
            vol[ChunkVolume.index(x, y, z)] = rng.nextBoolean() ? AIR : BUILD_FIRST + rng.nextInt(BUILD_COUNT);
        }
    }

    private static void build(int[] vol, Random rng) {
        int w = 4 + rng.nextInt(10), d = 4 + rng.nextInt(10), h = 3 + rng.nextInt(10);
        int x0 = rng.nextInt(16 - Math.min(w, 15)), z0 = rng.nextInt(16 - Math.min(d, 15));
        int y0 = SEA_LEVEL + 1 + rng.nextInt(30);
        int wall = BUILD_FIRST + rng.nextInt(BUILD_COUNT), floor = BUILD_FIRST + rng.nextInt(BUILD_COUNT);
        for (int x = x0; x < Math.min(16, x0 + w); x++) {
            for (int z = z0; z < Math.min(16, z0 + d); z++) {
                for (int y = y0; y < y0 + h && y < ChunkVolume.HEIGHT; y++) {
                    boolean shell = x == x0 || z == z0 || x == x0 + w - 1 || z == z0 + d - 1;
                    vol[ChunkVolume.index(x, y, z)] = y == y0 ? floor : shell ? wall : AIR;
                }
            }
        }
    }

    private static void machines(int[] vol, Random rng) {
        int changes = 30 + rng.nextInt(50);
        for (int i = 0; i < changes; i++) {
            int x = rng.nextInt(16), z = rng.nextInt(16), y = SEA_LEVEL + 1 + rng.nextInt(60);
            vol[ChunkVolume.index(x, y, z)] = MACHINE_FIRST + rng.nextInt(MACHINE_STATES);
        }
    }

    private static void explode(int[] vol, Random rng) {
        carve(vol, rng.nextInt(16), SEA_LEVEL - 10 + rng.nextInt(20), rng.nextInt(16), 3 + rng.nextInt(4));
    }
}
