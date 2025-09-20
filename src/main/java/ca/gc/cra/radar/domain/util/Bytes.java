package ca.gc.cra.radar.domain.util;

public final class Bytes {
  private Bytes() {}

  public static int u8(byte[] a, int off) {
    if (a == null || off < 0 || off >= a.length) {
      return 0;
    }
    return a[off] & 0xFF;
  }

  public static int u16be(byte[] a, int off) {
    if (a == null || off < 0 || off + 1 >= a.length) {
      return 0;
    }
    return ((a[off] & 0xFF) << 8) | (a[off + 1] & 0xFF);
  }

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
