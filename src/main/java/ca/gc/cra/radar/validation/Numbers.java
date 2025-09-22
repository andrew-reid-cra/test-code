package ca.gc.cra.radar.validation;

/**
 * Numeric validation helpers for CLI parsing.
 */
public final class Numbers {
  private Numbers() {
    // Utility
  }

  /**
   * Validates that a numeric value falls within the inclusive range.
   *
   * @param name logical parameter name
   * @param value candidate value
   * @param min minimum inclusive value
   * @param max maximum inclusive value
   * @return the validated value
   */
  public static long requireRange(String name, long value, long min, long max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          (name == null || name.isBlank() ? "value" : name)
              + " must be between " + min + " and " + max + " (was " + value + ")");
    }
    return value;
  }
}
