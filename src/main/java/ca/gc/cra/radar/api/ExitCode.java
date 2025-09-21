package ca.gc.cra.radar.api;

/**
 * Standard exit codes for RADAR command-line entry points.
 *
 * @since RADAR 0.2
 */
public enum ExitCode {
  SUCCESS(0),
  INVALID_ARGS(2),
  IO_ERROR(3),
  CONFIG_ERROR(4),
  RUNTIME_FAILURE(5),
  INTERRUPTED(130);

  private final int code;

  ExitCode(int code) {
    this.code = code;
  }

  /**
   * Returns the numeric value encoded by this exit code.
   *
   * @return numeric exit code
   */
  public int code() {
    return code;
  }
}
