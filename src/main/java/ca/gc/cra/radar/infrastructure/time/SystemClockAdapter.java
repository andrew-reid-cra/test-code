package ca.gc.cra.radar.infrastructure.time;

import ca.gc.cra.radar.application.port.ClockPort;

/**
 * {@link ClockPort} implementation backed by {@link System#currentTimeMillis()}.
 *
 * @since RADAR 0.1-doc
 */
public final class SystemClockAdapter implements ClockPort {
  /**
   * Creates a system clock adapter.
   *
   * @since RADAR 0.1-doc
   */
  public SystemClockAdapter() {}

  /**
   * Returns the current epoch milliseconds.
   *
   * @return current epoch milliseconds
   * @implNote Delegates to {@link System#currentTimeMillis()} without smoothing.
   * @since RADAR 0.1-doc
   */
  @Override
  public long nowMillis() {
    return System.currentTimeMillis();
  }
}

