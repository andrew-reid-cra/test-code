package ca.gc.cra.radar.config;

import java.util.Locale;

/**
 * <strong>What:</strong> Capture protocol hints used to align capture defaults with expected traffic families.
 * <p><strong>Why:</strong> Operands (GENERIC, TN3270) allow YAML configuration to select sensible BPF filters and
 * downstream heuristics.
 * <p><strong>Role:</strong> Configuration enum referenced by capture pipelines and validation utilities.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Expose human-friendly names for operator messaging.</li>
 *   <li>Identify protocol hints used to retrieve configured default BPF filters.</li>
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
  GENERIC("Generic"),
  /** Telnet TN3270/TN3270E traffic (TLS on 992 is common). */
  TN3270("TN3270");

  private final String displayName;

  CaptureProtocol(String displayName) {
    this.displayName = displayName;
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
