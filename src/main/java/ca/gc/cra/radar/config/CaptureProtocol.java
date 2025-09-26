package ca.gc.cra.radar.config;

import java.util.Locale;

/**
 * <strong>What:</strong> Capture protocol hints used to select sensible defaults for packet filtering.
 * <p><strong>Why:</strong> Allows CLI defaults to align BPF expressions and display names with expected protocol traffic.</p>
 * <p><strong>Role:</strong> Configuration enum referenced by capture pipelines and validation utilities.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Expose human-friendly names for operator messaging.</li>
 *   <li>Provide default Berkeley Packet Filter expressions per protocol.</li>
 *   <li>Parse user-supplied strings into canonical enum values.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Enum constants are immutable and globally shareable.</p>
 * <p><strong>Performance:</strong> String parsing relies on upper-casing and switch logic; O(n) in the input length.</p>
 * <p><strong>Observability:</strong> Values appear in CLI help, logs, and metrics tags (e.g., {@code capture.protocol}).</p>
 *
 * @since 0.1.0
 */
public enum CaptureProtocol {
  /** Generic TCP capture without protocol-specific hints. */
  GENERIC("Generic", "tcp"),
  /** Telnet TN3270/TN3270E traffic (TLS on 992 is common). */
  TN3270("TN3270", "tcp and (port 23 or port 992)");

  private final String displayName;
  private final String defaultFilter;

  CaptureProtocol(String displayName, String defaultFilter) {
    this.displayName = displayName;
    this.defaultFilter = defaultFilter;
  }

  /**
   * Returns a human-friendly display name for logs and CLI help text.
   *
   * @return localized display name
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent access.</p>
   * <p><strong>Performance:</strong> Constant-time accessor.</p>
   * <p><strong>Observability:</strong> Used when printing capture configuration.</p>
   */
  public String displayName() {
    return displayName;
  }

  /**
   * Returns the default Berkeley Packet Filter expression associated with the protocol.
   *
   * @return BPF expression string suitable for libpcap
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor.</p>
   * <p><strong>Performance:</strong> Constant-time.</p>
   * <p><strong>Observability:</strong> Surfaces in logs when configuring capture devices.</p>
   */
  public String defaultFilter() {
    return defaultFilter;
  }

  /**
   * Parses a capture protocol string into the corresponding enum value.
   *
   * @param value raw protocol string; {@code null} or blank yields {@link #GENERIC}
   * @return parsed protocol
   * @throws IllegalArgumentException if the value is not recognized
   *
   * <p><strong>Concurrency:</strong> Thread-safe; no global state.</p>
   * <p><strong>Performance:</strong> O(n) normalization followed by constant-time switch matching.</p>
   * <p><strong>Observability:</strong> Callers should surface parse failures to operators.</p>
   */
  public static CaptureProtocol fromString(String value) {
    if (value == null || value.isBlank()) {
      return GENERIC;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "TN3270", "TN3270E" -> TN3270;
      case "GENERIC", "DEFAULT", "TCP" -> GENERIC;
      default -> throw new IllegalArgumentException(
          "protocol must be one of GENERIC or TN3270 (was " + value + ")");
    };
  }
}
