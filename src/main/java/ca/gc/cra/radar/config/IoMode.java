package ca.gc.cra.radar.config;

/**
 * <strong>What:</strong> IO strategies available to RADAR pipelines.
 * <p><strong>Why:</strong> Determines whether pipelines operate on local files or Kafka topics.</p>
 * <p><strong>Role:</strong> Configuration enum referenced by capture/assemble/poster use cases.</p>
 * <p><strong>Thread-safety:</strong> Enum constants are immutable.</p>
 * <p><strong>Performance:</strong> Constant-time comparisons and parsing.</p>
 * <p><strong>Observability:</strong> Values appear in logs and metrics (e.g., {@code io.mode}).</p>
 *
 * @since 0.1.0
 */
public enum IoMode {
  /** File-system backed IO. */
  FILE,
  /** Apache Kafka backed IO. */
  KAFKA;

  /**
   * Parses a string into an {@link IoMode}, defaulting to {@link #FILE} when blank.
   *
   * @param value textual representation such as {@code "file"} or {@code "kafka"}
   * @return parsed mode
   * @throws IllegalArgumentException if the string does not match a known mode
   *
   * <p><strong>Concurrency:</strong> Thread-safe static utility.</p>
   * <p><strong>Performance:</strong> Normalizes input and dispatches via {@link Enum#valueOf(Class, String)}.</p>
   * <p><strong>Observability:</strong> Callers should surface parsing errors to operators.</p>
   */
  public static IoMode fromString(String value) {
    if (value == null || value.isBlank()) {
      return FILE;
    }
    try {
      return IoMode.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown ioMode: " + value, ex);
    }
  }
}
