package ca.gc.cra.radar.infrastructure.metrics;

import ca.gc.cra.radar.application.port.MetricsPort;

/**
 * Metrics adapter that discards all observations.
 * <p>Thread-safe and stateless; useful for tests or disabled metrics.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class NoOpMetricsAdapter implements MetricsPort {
  /**
   * Creates a no-op metrics adapter.
   *
   * @since RADAR 0.1-doc
   */
  public NoOpMetricsAdapter() {}
  /**
   * Discards the increment request.
   *
   * @param key metric identifier; ignored
   * @since RADAR 0.1-doc
   */
  @Override
  public void increment(String key) {}

  /**
   * Discards the observation.
   *
   * @param key metric identifier; ignored
   * @param value observed value; ignored
   * @since RADAR 0.1-doc
   */
  @Override
  public void observe(String key, long value) {}
}
