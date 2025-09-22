
package perf;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public final class BenchmarkRunner {
  private BenchmarkRunner() {}

  public static void main(String[] args) throws RunnerException {
    Options options =
        new OptionsBuilder()
            .include("ca.gc.cra.radar.benchmarks.HttpParseBenchmark")
            .include("ca.gc.cra.radar.benchmarks.ChunkedTransferBenchmark")
            .include("ca.gc.cra.radar.benchmarks.Tn3270ScanBenchmark")
            .shouldFailOnError(true)
            .forks(0)
            .build();
    new Runner(options).run();
  }
}
