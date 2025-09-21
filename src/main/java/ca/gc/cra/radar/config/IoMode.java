package ca.gc.cra.radar.config;

/**
 * IO strategies available to RADAR pipelines.
 *
 * @since RADAR 0.1-doc
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
   * @since RADAR 0.1-doc
   */
  public static IoMode fromString(String value) {
    if (value == null || value.isBlank()) {
      return FILE;
    }
    try {
      return IoMode.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown ioMode: " + value);
    }
  }
}
