package ca.gc.cra.radar.application.port;

/**
 * <strong>What:</strong> Domain port supplying wall-clock timestamps to message reconstruction flows.
 * <p><strong>Why:</strong> Provides deterministic time source abstraction for capture -> assemble -> sink pipelines.
 * <p><strong>Role:</strong> Domain port consumed by application use cases.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Expose the current epoch time in milliseconds.</li>
 *   <li>Allow adapters to inject deterministic clocks for testing.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations must be thread-safe; clock reads may occur on multiple
 * capture and persistence threads.</p>
 * <p><strong>Performance:</strong> Expected to be constant-time.</p>
 * <p><strong>Observability:</strong> No direct metrics; downstream components may tag events with returned values.</p>
 *
 * @implNote Default implementation delegates to {@link System#currentTimeMillis()}.
 * @since 0.1.0
 * @see ca.gc.cra.radar.infrastructure.time.SystemClockAdapter
 */
public interface ClockPort {
  /**
   * Returns the current epoch time in milliseconds.
   *
   * @return milliseconds since 1970-01-01T00:00:00Z; caller must treat as monotonically increasing but
   *         subject to system clock adjustments
   *
   * <p><strong>Concurrency:</strong> Must be safe for concurrent invocations.</p>
   * <p><strong>Performance:</strong> Expected to execute in constant time.</p>
   * <p><strong>Observability:</strong> No metrics; consumers attach timestamps to spans or logs.</p>
   */
  long nowMillis();

  /**
   * Default {@link ClockPort} using {@link System#currentTimeMillis()}.
   *
   * <p><strong>Concurrency:</strong> Thread-safe; delegates to JVM clock.</p>
   * <p><strong>Performance:</strong> Constant-time system call.</p>
   * <p><strong>Observability:</strong> Provides wall-clock timestamps without instrumentation.</p>
   */
  ClockPort SYSTEM = System::currentTimeMillis;
}
