package ca.gc.cra.radar.application.port;

/**
 * Provides access to wall-clock time for timestamping reconstructed events.
 *
 * @since RADAR 0.1-doc
 */
public interface ClockPort {
  /**
   * Returns the current epoch time in milliseconds.
   *
   * @return epoch milliseconds, matching {@link System#currentTimeMillis()}
   * @since RADAR 0.1-doc
   */
  long nowMillis();

  /**
   * Default implementation backed by {@link System#currentTimeMillis()}.
   *
   * @since RADAR 0.1-doc
   */
  ClockPort SYSTEM = System::currentTimeMillis;
}
