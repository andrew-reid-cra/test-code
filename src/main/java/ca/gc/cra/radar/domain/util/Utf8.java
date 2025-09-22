package ca.gc.cra.radar.domain.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Utility helpers for decoding UTF-8 segments without copying backing arrays first.
 */
public final class Utf8 {
  private Utf8() {}

  /**
   * Decodes a UTF-8 encoded slice from the provided array.
   *
   * @param data backing array; may be {@code null}
   * @param offset starting offset within the array
   * @param length number of bytes to decode
   * @return decoded string or an empty string when inputs are {@code null} or empty
   */
  public static String decode(byte[] data, int offset, int length) {
    if (data == null || length <= 0) {
      return "";
    }
    int start = Math.max(0, Math.min(data.length, offset));
    int len = Math.max(0, Math.min(length, data.length - start));
    if (len == 0) {
      return "";
    }
    return new String(data, start, len, StandardCharsets.UTF_8);
  }

  /**
   * Decodes the remaining bytes of a {@link ByteBuffer} as UTF-8 without mutating its position.
   *
   * @param buffer buffer to decode; may be {@code null}
   * @return decoded string or an empty string when the buffer is {@code null} or empty
   */
  public static String decode(ByteBuffer buffer) {
    if (buffer == null || !buffer.hasRemaining()) {
      return "";
    }
    ByteBuffer readOnly = buffer.asReadOnlyBuffer();
    return StandardCharsets.UTF_8.decode(readOnly).toString();
  }
}
