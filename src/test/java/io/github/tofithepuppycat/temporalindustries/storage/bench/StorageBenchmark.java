package io.github.tofithepuppycat.temporalindustries.storage.bench;

import io.github.tofithepuppycat.temporalindustries.storage.CachingStore;
import io.github.tofithepuppycat.temporalindustries.storage.ChunkDiff;
import io.github.tofithepuppycat.temporalindustries.storage.ChunkHistoryStore;
import io.github.tofithepuppycat.temporalindustries.storage.ChunkVolume;
import io.github.tofithepuppycat.temporalindustries.storage.FullCopyStore;
import io.github.tofithepuppycat.temporalindustries.storage.ReverseDeltaStore;
import io.github.tofithepuppycat.temporalindustries.storage.RleDeltaChainStore;
import io.github.tofithepuppycat.temporalindustries.storage.SectionDedupStore;
import io.github.tofithepuppycat.temporalindustries.storage.SectionDeltaStore;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.zip.Deflater;

/**
 * Runs every storage strategy over every synthetic workload and writes CSVs for plot_results.py.
 * Usage: StorageBenchmark [outputDir] [versions]
 */
public final class StorageBenchmark {
    static final long SEED = 20260101L;
    static final int[] SIZE_CHECKPOINTS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512};
    static final int[] DISTANCES = {1, 2, 4, 8, 16, 32, 64, 128, 255};

    static final List<Supplier<ChunkHistoryStore>> STORES = List.of(
            FullCopyStore::new, RleDeltaChainStore::new, SectionDedupStore::new, ReverseDeltaStore::new,
            SectionDeltaStore::new);

    private final Path outDir;
    private final int versions;

    private StorageBenchmark(Path outDir, int versions) {
        this.outDir = outDir;
        this.versions = versions;
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/benchmark");
        int versions = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        Files.createDirectories(out);
        new StorageBenchmark(out, versions).run();
    }

    private void run() throws IOException {
        try (PrintWriter size = csv("size.csv", "workload,store,versions,raw_bytes,deflated_bytes");
             PrintWriter commit = csv("commit.csv", "workload,store,mean_ms,p95_ms");
             PrintWriter resolve = csv("resolve.csv", "workload,store,distance,resolve_us");
             PrintWriter diff = csv("diff.csv", "workload,store,distance,direction,diff_us,changed_blocks,returned_blocks");
             PrintWriter scrub = csv("scrub.csv", "workload,store,cache,total_ms")) {
            for (Workload workload : Workload.values()) {
                System.out.println("== " + workload.label);
                List<int[]> history = workload.generate(versions, SEED);
                verify(history.subList(0, Math.min(48, history.size())));
                measureSizeAndCommit(workload, history, size, commit);
                measureQueries(workload, history, resolve, diff, scrub);
            }
        }
        System.out.println("Wrote CSVs to " + outDir.toAbsolutePath());
    }

    private PrintWriter csv(String name, String header) throws IOException {
        PrintWriter w = new PrintWriter(Files.newBufferedWriter(outDir.resolve(name)));
        w.println(header);
        return w;
    }

    /** Throws if any strategy fails to reproduce a version or a diff exactly. */
    public static void verify(List<int[]> history) {
        Random rng = new Random(7);
        for (Supplier<ChunkHistoryStore> factory : STORES) {
            ChunkHistoryStore store = factory.get();
            for (int[] volume : history) store.commit(volume);
            for (int v = 0; v < history.size(); v++) {
                if (!Arrays.equals(history.get(v), store.resolve(v))) {
                    throw new IllegalStateException(store.name() + " resolved version " + v + " incorrectly");
                }
            }
            for (int i = 0; i < 40; i++) {
                int from = rng.nextInt(history.size()), to = rng.nextInt(history.size());
                int[] state = history.get(from).clone();
                ChunkDiff d = store.diff(from, to);
                ChunkVolume.apply(state, d);
                if (!Arrays.equals(state, history.get(to))) {
                    throw new IllegalStateException(store.name() + " diff " + from + "->" + to + " incorrect");
                }
            }
        }
    }

    private void measureSizeAndCommit(Workload workload, List<int[]> history, PrintWriter size, PrintWriter commit) {
        for (Supplier<ChunkHistoryStore> factory : STORES) {
            ChunkHistoryStore warmup = factory.get();
            for (int[] volume : history) warmup.commit(volume);
            List<Double> times = new ArrayList<>();
            ChunkHistoryStore kept = null;
            for (int rep = 0; rep < 3; rep++) {
                ChunkHistoryStore store = factory.get();
                for (int v = 0; v < history.size(); v++) {
                    long t0 = System.nanoTime();
                    store.commit(history.get(v));
                    if (v > 0) times.add((System.nanoTime() - t0) / 1e6);
                    if (rep == 0 && isCheckpoint(v + 1)) {
                        byte[] raw = store.serialize();
                        size.printf("%s,%s,%d,%d,%d%n", workload.label, store.name(), v + 1, raw.length, deflatedSize(raw));
                    }
                }
                kept = store;
            }
            times.sort(Double::compare);
            double mean = times.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            commit.printf("%s,%s,%.4f,%.4f%n", workload.label, kept.name(), mean, times.get((int) (times.size() * 0.95)));
        }
    }

    private static boolean isCheckpoint(int count) {
        return Arrays.stream(SIZE_CHECKPOINTS).anyMatch(c -> c == count);
    }

    static long deflatedSize(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        byte[] buf = new byte[65536];
        long total = 0;
        while (!deflater.finished()) total += deflater.deflate(buf);
        deflater.end();
        return total;
    }

    private void measureQueries(Workload workload, List<int[]> history, PrintWriter resolve, PrintWriter diff, PrintWriter scrub) {
        int head = history.size() - 1;
        for (Supplier<ChunkHistoryStore> factory : STORES) {
            ChunkHistoryStore store = factory.get();
            for (int[] volume : history) store.commit(volume);

            for (int distance : DISTANCES) {
                if (distance > head) continue;
                int version = head - distance;
                double us = medianMicros(() -> store.resolve(version), 41);
                resolve.printf("%s,%s,%d,%.2f%n", workload.label, store.name(), distance, us);

                for (String direction : new String[]{"rollback", "forward"}) {
                    boolean rollback = direction.equals("rollback");
                    int from = rollback ? head : version, to = rollback ? version : head;
                    int truth = ChunkVolume.scan(history.get(from), history.get(to)).size();
                    ChunkDiff[] last = new ChunkDiff[1];
                    double diffUs = medianMicros(() -> last[0] = store.diff(from, to), 41);
                    diff.printf("%s,%s,%d,%s,%.2f,%d,%d%n", workload.label, store.name(), distance, direction,
                            diffUs, truth, last[0].size());
                }
            }

            for (boolean cached : new boolean[]{false, true}) {
                int[] walk = scrubWalk(history.size());
                double ms = median(5, () -> {
                    ChunkHistoryStore s = cached ? new CachingStore(store, 16) : store;
                    long t0 = System.nanoTime();
                    int sink = 0;
                    for (int v : walk) sink += s.resolve(v)[0];
                    if (sink == Integer.MIN_VALUE) System.out.print("");
                    return (System.nanoTime() - t0) / 1e6;
                });
                scrub.printf("%s,%s,%s,%.2f%n", workload.label, store.name(), cached ? "lru16" : "none", ms);
            }
        }
    }

    /** A timeline slider drag: a random walk with small steps and occasional long jumps. */
    private static int[] scrubWalk(int versionCount) {
        Random rng = new Random(11);
        int[] walk = new int[300];
        int pos = versionCount - 1;
        for (int i = 0; i < walk.length; i++) {
            pos = rng.nextInt(20) == 0 ? rng.nextInt(versionCount) : Math.max(0, Math.min(versionCount - 1, pos + rng.nextInt(7) - 3));
            walk[i] = pos;
        }
        return walk;
    }

    private static double medianMicros(Runnable task, int reps) {
        for (int i = 0; i < 20; i++) task.run();
        return median(reps, () -> {
            long t0 = System.nanoTime();
            task.run();
            return (System.nanoTime() - t0) / 1e3;
        });
    }

    private static double median(int reps, Supplier<Double> measurement) {
        double[] values = new double[reps];
        for (int i = 0; i < reps; i++) values[i] = measurement.get();
        Arrays.sort(values);
        return values[reps / 2];
    }
}
