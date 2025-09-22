# RADAR Pipeline Performance Notes

## Benchmark Summary

| Benchmark | Mode | Baseline (origin/main) | Optimized (current) | Delta |
|-----------|------|------------------------|---------------------|-------|
| HttpParseBenchmark.parseRequest | Throughput | 2869 ops/ms | 4282 ops/ms | +49.3% |
| HttpParseBenchmark.parseRequest | Allocation | 2720 B/op | 24 B/op | -99.1% |

*Baseline measurements collected from a clean worktree checked out at `origin/main` with the new JMH harness copied in for comparability. Optimized numbers captured from the updated worktree (`java -Djmh.ignoreLock=true -cp target/test-classes;target/classes;$(Get-Content target/cp.txt) org.openjdk.jmh.Main -wi 2 -i 5 -w 1s -r 1s -f 1 -t 1 -prof gc ca.gc.cra.radar.benchmarks.HttpParseBenchmark.parseRequest`).*

## Observations

- Throughput improved substantially once `DirectionState` buffers are reused (+49% vs baseline) while allocations dropped below 25 B/op, comfortably clearing the parser target.
- `ChunkedTransferBenchmark` and `Tn3270ScanBenchmark` now complete without runaway memory usage after resetting reconstructor state on every invocation.
- Fresh 10s JMH runs (same JVM, non-forked) report `ChunkedTransferBenchmark.parseChunked` at ~1.78k ops/ms and `Tn3270ScanBenchmark.parseRecord` holding ~1.07k ops/ms, both stable across iterations.
- `LiveProcessingUseCaseConcurrencyTest` exercises the new worker/queue configuration to ensure back-pressure correctness under load.

## GC/Latency Sampling

- JMH `-prof gc` sampling on `HttpParseBenchmark.parseRequest` (optimized build) now reports ~88 MB/s allocation rate with 24 B/op, dominated by per-message envelope objects rather than buffer copies.
- Baseline allocation rate was ~5.3 GB/s with 2.7 KB/op, so the current improvements represent a ~60x reduction in allocation pressure.

## Reproduction

1. Build tests: `mvn -DskipTests=false test`
2. Generate benchmark classpath: `mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -DincludeScope=test`
3. Run HTTP parse benchmark with GC profiling:
   ```powershell
   $cp = Get-Content target\cp.txt
   $classpath = "target\test-classes;target\classes;" + $cp
   java -Djmh.ignoreLock=true -cp $classpath org.openjdk.jmh.Main -wi 2 -i 5 -w 1s -r 1s -f 1 -t 1 -prof gc ca.gc.cra.radar.benchmarks.HttpParseBenchmark.parseRequest
   ```

## Next Steps

- Audit remaining per-event objects in `HttpMessageReconstructor` (metadata Map instances, transient `ArrayList`) to see if further pooling yields measurable wins, though the allocation budget is already well under the 25% goal.
- Replace manual JSON formatting in segment sinks with a pre-sized `StringBuilder` that reuses buffers between calls to trim allocation footprint.
- Profile `Tn3270MessageReconstructor` hot paths with async-profiler to ensure new decoder avoids redundant byte copying.
- Extend documentation with load-harness results once `perf/LoadHarness.java` execution is stabilized.
