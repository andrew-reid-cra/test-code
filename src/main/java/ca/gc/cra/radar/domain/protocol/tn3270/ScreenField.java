package ca.gc.cra.radar.domain.protocol.tn3270;

import java.util.Objects;

/**
 * Immutable representation of a 3270 screen field.
 *
 * @param start zero-based linear offset into the 3270 buffer
 * @param length length of the field in bytes (excluding the attribute byte)
 * @param protectedField whether the field is protected (host-only)
 * @param numeric whether the field is numeric-only
 * @param hidden whether the field is hidden/non-display
 * @param value decoded field value (right-trimmed); never {@code null}
 *
 * @since RADAR 0.2.0
 */
public record ScreenField(
    int start,
    int length,
    boolean protectedField,
    boolean numeric,
    boolean hidden,
    String value) {

  /**
   * Creates a screen field ensuring invariants on start/length/value.
   *
   * @throws IllegalArgumentException if {@code start < 0} or {@code length < 0}
   * @throws NullPointerException if {@code value} is {@code null}
   */
  public ScreenField {
    if (start < 0) {
      throw new IllegalArgumentException("start must be >= 0 (was " + start + ')');
    }
    if (length < 0) {
      throw new IllegalArgumentException("length must be >= 0 (was " + length + ')');
    }
    value = Objects.requireNonNull(value, "value");
  }
}
