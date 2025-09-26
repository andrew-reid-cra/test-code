package ca.gc.cra.radar.validation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * <strong>What:</strong> Validation utilities for strings used by RADAR configuration and CLI layers.
 * <p><strong>Why:</strong> Ensures capture, assemble, and sink stages start with sanitized identifiers
 * and credentials so downstream adapters avoid undefined broker, filesystem, or protocol behavior.
 * <p><strong>Role:</strong> Domain support utilities invoked before ports/adapters allocate external resources.
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Reject blank or control-character inputs supplied via CLI or config files.</li>
 *   <li>Normalize Kafka topic identifiers (capture -> sink) to the supported character set.</li>
 *   <li>Verify printable ASCII constraints for observability-friendly naming.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Immutable stateless utilities; safe for concurrent access.</p>
 * <p><strong>Performance:</strong> O(n) character scans with minimal allocations (trimmed copy only when needed).</p>
 * <p><strong>Observability:</strong> No metrics or logs; validation failures raise {@link IllegalArgumentException}.</p>
 *
 * @implNote Control characters are detected via {@link Character#isISOControl(char)} to align with Kafka and
 * filesystem constraints.
 * @since 0.1.0
 * @see Numbers
 * @see Paths
 */
public final class Strings {
  private static final Pattern TOPIC_PATTERN = Pattern.compile("^([A-Za-z0-9._-]+)$");

  private Strings() {
    // Utility
  }

  /**
   * Ensures a candidate string is non-null, non-blank, and control-character free.
   *
   * @param name logical parameter name for diagnostics; if {@code null} defaults to {@code "value"}
   * @param value candidate text; must not be {@code null}
   * @return trimmed input with leading/trailing whitespace removed; caller owns the result
   * @throws NullPointerException if {@code value} is {@code null}
   * @throws IllegalArgumentException if the trimmed value is blank or contains ISO control characters
   *
   * <p><strong>Concurrency:</strong> Thread-safe; method uses only locals.</p>
   * <p><strong>Performance:</strong> Single pass trim and character scan; O(n) on the input length.</p>
   * <p><strong>Observability:</strong> No logs or metrics emitted; violations raise exceptions for upstream handling.</p>
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
   * Validates and normalizes a Kafka topic or similar identifier using the default label {@code "topic"}.
   *
   * @param topic candidate topic; must be non-null
   * @return sanitized topic string composed of {@code [A-Za-z0-9._-]}; caller must not mutate
   * @throws NullPointerException if {@code topic} is {@code null}
   * @throws IllegalArgumentException if the normalized topic violates the supported character class
   *
   * <p><strong>Concurrency:</strong> Thread-safe wrapper delegating to {@link #sanitizeTopic(String, String)}.</p>
   * <p><strong>Performance:</strong> O(n) validation work; regular expression reuses a precompiled {@link Pattern}.</p>
   * <p><strong>Observability:</strong> No metrics; failures manifest as {@link IllegalArgumentException}s.</p>
   */
  public static String sanitizeTopic(String topic) {
    return sanitizeTopic("topic", topic);
  }

  /**
   * Validates and normalizes a Kafka topic-like identifier with a custom diagnostic label.
   *
   * @param name logical parameter name included in exception messages; must be non-null/non-blank
   * @param topic candidate topic; must be non-null
   * @return sanitized topic string matching {@code [A-Za-z0-9._-]+}; caller must not mutate
   * @throws NullPointerException if {@code topic} is {@code null}
   * @throws IllegalArgumentException if {@code name} is blank or the topic contains unsupported characters
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent access.</p>
   * <p><strong>Performance:</strong> O(n) trim and regex match; uses JVM regex DFA without extra allocations.</p>
   * <p><strong>Observability:</strong> Emits no logs; callers should surface failures with CLI help text.</p>
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
   * Ensures a value contains only printable ASCII characters and is within the supplied length budget.
   *
   * @param name logical name for diagnostics; must be non-null/non-blank
   * @param value candidate string; must be non-null
   * @param maxLength maximum permitted length in characters; negative values reject all candidates
   * @return validated value containing only characters {@code 0x20-0x7E}; caller owns the result
   * @throws NullPointerException if {@code value} is {@code null}
   * @throws IllegalArgumentException if {@code name} is blank, the value exceeds {@code maxLength}, or contains
   *         non-printable ASCII characters
   *
   * <p><strong>Concurrency:</strong> Thread-safe stateless validation.</p>
   * <p><strong>Performance:</strong> Linear scan across the string; bounded by {@code maxLength} comparisons.</p>
   * <p><strong>Observability:</strong> Does not emit metrics; violations bubble up as {@link IllegalArgumentException}s.</p>
   * <p><strong>TODO:</strong> Add explicit validation for negative {@code maxLength} inputs to improve diagnostics.</p>
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
