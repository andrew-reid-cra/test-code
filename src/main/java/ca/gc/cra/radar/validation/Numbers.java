package ca.gc.cra.radar.validation;

/**
 * <strong>What:</strong> Numeric validation helpers used by RADAR CLI and configuration parsing.
 * <p><strong>Why:</strong> Guards against invalid capture (timeouts), assemble (queue depths), and sink
 * (batch sizing) parameters before ports allocate resources.
 * <p><strong>Role:</strong> Domain support utilities invoked by adapters and configuration loaders.
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Enforce inclusive numeric bounds declared in configuration schemas.</li>
 *   <li>Provide consistent error messaging for CLI feedback.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Immutable stateless utility.
 * <p><strong>Performance:</strong> Constant-time range checks.
 * <p><strong>Observability:</strong> Emits no metrics or logs; throws {@link IllegalArgumentException} when
 * validation fails.
 *
 * @implNote Currently operates on primitive long bounds; extend with TODO: support arbitrary precision if
 * configuration introduces larger numeric domains.
 * @since 0.1.0
 * @see Strings
 */
public final class Numbers {
  private Numbers() {
    // Utility
  }

  /**
   * Validates that a numeric value falls within an inclusive range.
   *
   * @param name logical parameter name included in diagnostics; defaults to {@code "value"} when blank
   * @param value candidate value expressed in the caller's units (e.g., bytes, ms)
   * @param min minimum inclusive value in the same units as {@code value}
   * @param max maximum inclusive value in the same units as {@code value}
   * @return the validated value for fluent call sites
   * @throws IllegalArgumentException if {@code value} lies outside {@code [min, max]}
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent use.</p>
   * <p><strong>Performance:</strong> Constant-time comparisons.</p>
   * <p><strong>Observability:</strong> No metrics emitted; violations raise {@link IllegalArgumentException}.</p>
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
