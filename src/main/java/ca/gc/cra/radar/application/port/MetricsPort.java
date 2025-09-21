package ca.gc.cra.radar.application.port;

/**
 * Metrics abstraction for recording counters and distribution samples.
 *
 * @since RADAR 0.1-doc
 */
public interface MetricsPort {
  /**
   * Increments the named counter by one.
   *
   * @param key metric identifier scoped by the caller; must not be {@code null}
   * @since RADAR 0.1-doc
   */
  void increment(String key);

  /**
   * Records an observation for a histogram/gauge style metric.
   *
   * @param key metric identifier scoped by the caller
   * @param value observed numeric value; interpretation defined by the caller
   * @since RADAR 0.1-doc
   */
  void observe(String key, long value);

  /**
   * Metrics implementation that ignores all updates.
   *
   * @since RADAR 0.1-doc
   */
  MetricsPort NO_OP = new MetricsPort() {
    @Override public void increment(String key) {}
    @Override public void observe(String key, long value) {}
  };
}
