package ca.gc.cra.radar.domain.util;

/** Delegate to legacy sniffer.common.Bytes utilities until fully migrated. */
public final class Bytes {
  private Bytes() {}

  public static int u8(byte[] a, int off) {
    return sniffer.common.Bytes.u8(a, off);
  }

  public static int u16be(byte[] a, int off) {
    return sniffer.common.Bytes.u16be(a, off);
  }

  public static int u32be(byte[] a, int off) {
    return sniffer.common.Bytes.u32be(a, off);
  }
}


