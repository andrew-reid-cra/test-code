package ca.gc.cra.radar.domain.util;

/**
 * <strong>What:</strong> Utility methods for reading unsigned integers from byte arrays.
 * <p><strong>Why:</strong> Supports protocol decoders that operate on raw byte buffers.</p>
 * <p><strong>Role:</strong> Domain support functions reused across adapters.</p>
 * <p><strong>Thread-safety:</strong> Stateless static helpers; safe for concurrent use.</p>
 * <p><strong>Performance:</strong> Constant-time bit manipulations; avoid bounds exceptions by returning 0 on invalid input.</p>
 * <p><strong>Observability:</strong> No direct instrumentation; callers may log when zero indicates malformed data.</p>
 *
 * @since 0.1.0
 */
public final class Bytes {
  private Bytes() {}

  /**
   * Reads an unsigned 8-bit value.
   *
   * @param a byte array source; may be {@code null}
   * @param off offset in the array
   * @return unsigned value in the range {@code [0,255]} or {@code 0} if out of bounds
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent calls.</p>
   * <p><strong>Performance:</strong> Constant-time array access.</p>
   * <p><strong>Observability:</strong> Callers may treat {@code 0} from invalid offsets as a signal to record parse errors.</p>
   */
  public static int u8(byte[] a, int off) {
    if (a == null || off < 0 || off >= a.length) {
      return 0;
    }
    return a[off] & 0xFF;
  }

  /**
   * Reads an unsigned 16-bit big-endian value.
   *
   * @param a byte array source; may be {@code null}
   * @param off offset of the first byte
   * @return unsigned value or {@code 0} when insufficient bytes remain
   *
   * <p><strong>Concurrency:</strong> Thread-safe static method.</p>\n   * <p><strong>Performance:</strong> Constant-time bit operations.</p>
   * <p><strong>Observability:</strong> Callers should log when zero indicates truncated data.</p>
   */
  public static int u16be(byte[] a, int off) {
    if (a == null || off < 0 || off + 1 >= a.length) {
      return 0;
    }
    return ((a[off] & 0xFF) << 8) | (a[off + 1] & 0xFF);
  }

  /**
   * Reads an unsigned 32-bit big-endian value.
   *
   * @param a byte array source; may be {@code null}
   * @param off offset of the most significant byte
   * @return unsigned value as an {@code int} (higher bits truncated) or {@code 0} if out of bounds
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent use.</p>
   * <p><strong>Performance:</strong> Constant-time bit operations.</p>
   * <p><strong>Observability:</strong> Callers should treat {@code 0} from invalid offsets as an indicator of malformed frames.</p>
   */
  public static int u32be(byte[] a, int off) {
    if (a == null || off < 0 || off + 3 >= a.length) {
      return 0;
    }
    return ((a[off] & 0xFF) << 24)
        | ((a[off + 1] & 0xFF) << 16)
        | ((a[off + 2] & 0xFF) << 8)
        | (a[off + 3] & 0xFF);
  }
}

