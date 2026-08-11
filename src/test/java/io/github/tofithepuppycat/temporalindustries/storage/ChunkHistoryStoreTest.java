package io.github.tofithepuppycat.temporalindustries.storage;

import io.github.tofithepuppycat.temporalindustries.storage.bench.StorageBenchmark;
import io.github.tofithepuppycat.temporalindustries.storage.bench.Workload;
import org.junit.jupiter.api.Test;

class ChunkHistoryStoreTest {
    @Test
    void everyStrategyReproducesEveryWorkload() {
        for (Workload workload : Workload.values()) {
            StorageBenchmark.verify(workload.generate(80, 99L));
        }
    }
}
