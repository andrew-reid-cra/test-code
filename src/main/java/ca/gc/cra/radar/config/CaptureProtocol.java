package ca.gc.cra.radar.config;

import java.util.Locale;

/**
 * Capture protocol hints used to select sensible defaults for packet filtering.
 *
 * @since RADAR 0.1-doc
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

  /** Human-friendly display name for logs and help text. */
  public String displayName() {
    return displayName;
  }

  /** Default Berkeley Packet Filter expression associated with the protocol. */
  public String defaultFilter() {
    return defaultFilter;
  }

  /**
   * Parses a capture protocol string.
   *
   * @param value raw protocol string; null or blank yields {@link #GENERIC}
   * @return parsed protocol
   * @throws IllegalArgumentException if the value is not recognized
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
