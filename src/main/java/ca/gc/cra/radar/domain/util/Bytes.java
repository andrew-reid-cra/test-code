package ca.gc.cra.radar.domain.util;

/**
 * Utility methods for reading unsigned integers from byte arrays.
 *
 * @since RADAR 0.1-doc
 */
public final class Bytes {
  private Bytes() {}

  /**
   * Reads an unsigned 8-bit value.
   *
   * @param a byte array source; may be {@code null}
   * @param off offset in the array
   * @return unsigned value in the range {@code [0,255]} or {@code 0} if out of bounds
   * @since RADAR 0.1-doc
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
   * @since RADAR 0.1-doc
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
   * @since RADAR 0.1-doc
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
