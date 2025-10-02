package ca.gc.cra.radar.domain.protocol.tn3270;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Hash utilities for TN3270 derived values.
 *
 * @since RADAR 0.2.0
 */
public final class Tn3270Hashes {
  private Tn3270Hashes() {}

  /**
   * Computes a Murmur3 128-bit hash for the supplied UTF-8 string.
   *
   * @param value input value
   * @return lower-case hexadecimal digest
   */
  public static String murmur128Hex(CharSequence value) {
    if (value == null) {
      return null;
    }
    return murmur128Hex(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Computes a Murmur3 128-bit hash for the supplied bytes.
   *
   * @param data input byte array
   * @return lower-case hexadecimal digest
   */
  public static String murmur128Hex(byte[] data) {
    final long c1 = 0x87c37b91114253d5L;
    final long c2 = 0x4cf5ad432745937fL;
    long h1 = 0;
    long h2 = 0;
    int length = data.length;
    int roundedEnd = length & ~0x0F;

    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < roundedEnd; i += 16) {
      long k1 = buffer.getLong(i);
      long k2 = buffer.getLong(i + 8);

      k1 *= c1;
      k1 = Long.rotateLeft(k1, 31);
      k1 *= c2;
      h1 ^= k1;

      h1 = Long.rotateLeft(h1, 27);
      h1 += h2;
      h1 = h1 * 5 + 0x52dce729;

      k2 *= c2;
      k2 = Long.rotateLeft(k2, 33);
      k2 *= c1;
      h2 ^= k2;

      h2 = Long.rotateLeft(h2, 31);
      h2 += h1;
      h2 = h2 * 5 + 0x38495ab5;
    }

    long k1 = 0;
    long k2 = 0;
    int tailStart = roundedEnd;
    int tail = length & 15;

    if (tail > 8) {
      for (int idx = tail - 1; idx >= 8; idx--) {
        int shift = (idx - 8) * 8;
        k2 ^= (long) (data[tailStart + idx] & 0xFF) << shift;
      }
      k2 *= c2;
      k2 = Long.rotateLeft(k2, 33);
      k2 *= c1;
      h2 ^= k2;
    }

    if (tail > 0) {
      int upper = Math.min(tail - 1, 7);
      for (int idx = upper; idx >= 0; idx--) {
        k1 ^= (long) (data[tailStart + idx] & 0xFF) << (idx * 8);
      }
      k1 *= c1;
      k1 = Long.rotateLeft(k1, 31);
      k1 *= c2;
      h1 ^= k1;
    }

    h1 ^= length;
    h2 ^= length;

    h1 += h2;
    h2 += h1;

    h1 = fmix64(h1);
    h2 = fmix64(h2);

    h1 += h2;
    h2 += h1;

    byte[] out = new byte[16];
    ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putLong(0, h1).putLong(8, h2);
    StringBuilder hex = new StringBuilder(32);
    for (byte b : out) {
      hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
    }
    return hex.toString();
  }

  private static long fmix64(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return k;
  }
}
