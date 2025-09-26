package ca.gc.cra.radar.api;

/**
 * <strong>What:</strong> Canonical exit codes shared by RADAR command-line tools.
 * <p><strong>Why:</strong> Provides consistent process status semantics so operators and automation can react deterministically.</p>
 * <p><strong>Role:</strong> Adapter-facing enum returned by CLI entry points.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Enumerate well-known success and failure outcomes.</li>
 *   <li>Expose the numeric value consumed by operating systems and scripts.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Enum constants are immutable and thread-safe.</p>
 * <p><strong>Performance:</strong> Constant-time lookups; values are eagerly initialized.</p>
 * <p><strong>Observability:</strong> Values should surface in logs/metrics (e.g., {@code cli.exit_code}).</p>
 *
 * @since 0.1.0
 */
public enum ExitCode {
  /** Successful execution. */
  SUCCESS(0),
  /** Command-line arguments were invalid. */
  INVALID_ARGS(2),
  /** IO failure occurred while running the CLI. */
  IO_ERROR(3),
  /** Configuration was missing or malformed. */
  CONFIG_ERROR(4),
  /** Unexpected runtime failure occurred. */
  RUNTIME_FAILURE(5),
  /** Process was interrupted (e.g., SIGINT). */
  INTERRUPTED(130);

  private final int code;

  ExitCode(int code) {
    this.code = code;
  }

  /**
   * Returns the numeric value encoded by this exit code.
   *
   * @return numeric exit code
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent access.</p>
   * <p><strong>Performance:</strong> Constant-time accessor.</p>
   * <p><strong>Observability:</strong> Use when tagging metrics or logging exit statuses.</p>
   */
  public int code() {
    return code;
  }
}
