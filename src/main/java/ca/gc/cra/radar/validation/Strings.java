package ca.gc.cra.radar.validation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * String validation helpers for CLI and configuration parsing.
 */
public final class Strings {
  private static final Pattern TOPIC_PATTERN = Pattern.compile("^([A-Za-z0-9._-]+)$");

  private Strings() {
    // Utility
  }

  /**
   * Ensures a string value is non-null, non-blank, and free of control characters.
   *
   * @param name parameter name for error reporting
   * @param value candidate value
   * @return trimmed, validated value
   */
  public static String requireNonBlank(String name, String value) {
    String raw = Objects.requireNonNull(value, name == null ? "value" : name);
    if (containsControl(raw)) {
      throw new IllegalArgumentException(message(name, "must not contain control characters"));
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(message(name, "must not be blank"));
    }
    if (containsControl(trimmed)) {
      throw new IllegalArgumentException(message(name, "must not contain control characters"));
    }
    return trimmed;
  }

  /**
   * Validates and normalizes a Kafka topic or similar identifier.
   *
   * @param topic candidate topic
   * @return sanitized topic that matches {@code [A-Za-z0-9._-]+}
   */
  public static String sanitizeTopic(String topic) {
    return sanitizeTopic("topic", topic);
  }

  /**
   * Validates and normalizes a Kafka topic or similar identifier with custom naming.
   *
   * @param name logical parameter name
   * @param topic candidate topic
   * @return sanitized topic string
   */
  public static String sanitizeTopic(String name, String topic) {
    String sanitized = requireNonBlank(name, topic);
    if (!TOPIC_PATTERN.matcher(sanitized).matches()) {
      throw new IllegalArgumentException(message(name,
          "must only contain letters, digits, dot, underscore, or hyphen"));
    }
    return sanitized;
  }

  /**
   * Ensures a value is composed of printable ASCII characters.
   *
   * @param name logical name for error reporting
   * @param value candidate string
   * @param maxLength maximum allowed length
   * @return validated value
   */
  public static String requirePrintableAscii(String name, String value, int maxLength) {
    String sanitized = requireNonBlank(name, value);
    if (sanitized.length() > maxLength) {
      throw new IllegalArgumentException(message(name, "length must be <= " + maxLength));
    }
    for (int i = 0; i < sanitized.length(); i++) {
      char c = sanitized.charAt(i);
      if (c < 0x20 || c > 0x7E) {
        throw new IllegalArgumentException(message(name, "must contain printable ASCII characters"));
      }
    }
    return sanitized;
  }

  private static boolean containsControl(CharSequence value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static String message(String name, String suffix) {
    String label = (name == null || name.isBlank()) ? "value" : name;
    return label + " " + suffix;
  }
}

