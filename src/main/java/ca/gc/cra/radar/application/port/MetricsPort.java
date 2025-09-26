package ca.gc.cra.radar.application.port;

/**
 * <strong>What:</strong> Domain port abstracting RADAR metrics emission.
 * <p><strong>Why:</strong> Allows pipelines to record counters and latency observations without binding to a vendor SDK.</p>
 * <p><strong>Role:</strong> Domain port implemented by adapters such as {@code OpenTelemetryMetricsAdapter}.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Expose counter increments for events like packet drops or message successes.</li>
 *   <li>Record numeric observations for latency, byte counts, or queue depths.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations must be safe for concurrent updates from capture, assemble, and sink threads.</p>
 * <p><strong>Performance:</strong> Calls should be non-blocking and amortized O(1); adapters may batch when supported.</p>
 * <p><strong>Observability:</strong> Defines the metric name contract (e.g., {@code live.persist.latencyNanos}).</p>
 *
 * @implNote Consumers must not pass {@code null} metric keys; adapters may normalize names.
 * @since 0.1.0
 */
public interface MetricsPort {
  /**
   * Increments the named counter by one.
   *
   * @param key metric identifier using dotted RADAR naming (e.g., {@code capture.frames.dropped}); must not be {@code null}
   *
   * <p><strong>Concurrency:</strong> Safe to call from any thread.</p>
   * <p><strong>Performance:</strong> Expected O(1); adapters may buffer increments.</p>
   * <p><strong>Observability:</strong> Adds one to the corresponding counter.</p>
   */
  void increment(String key);

  /**
   * Records an observation for a histogram/gauge style metric.
   *
   * @param key metric identifier using dotted RADAR naming; must not be {@code null}
   * @param value observed value (e.g., nanoseconds, bytes); semantics defined by the caller
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent invocation.</p>
   * <p><strong>Performance:</strong> Expected O(1); avoid blocking operations.</p>
   * <p><strong>Observability:</strong> Adds a sample to the named histogram/gauge.</p>
   */
  void observe(String key, long value);

  /**
   * Metrics implementation that ignores all updates.
   *
   * <p><strong>Concurrency:</strong> Thread-safe.</p>
   * <p><strong>Performance:</strong> Constant time no-op.</p>
   * <p><strong>Observability:</strong> Drops all metrics; useful for tests.</p>
   */
  MetricsPort NO_OP = new MetricsPort() {
    @Override public void increment(String key) {}

    @Override public void observe(String key, long value) {}
  };
}

