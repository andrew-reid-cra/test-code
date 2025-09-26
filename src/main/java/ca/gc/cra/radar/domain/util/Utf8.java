package ca.gc.cra.radar.domain.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * <strong>What:</strong> Utility helpers for decoding UTF-8 segments without copying backing arrays first.
 * <p><strong>Why:</strong> Allows protocol modules to turn byte slices into strings with predictable bounds checks.</p>
 * <p><strong>Role:</strong> Domain support class shared by adapters.</p>
 * <p><strong>Thread-safety:</strong> Stateless; safe for concurrent use.</p>
 * <p><strong>Performance:</strong> Uses JDK UTF-8 decoders directly on provided buffers to avoid intermediate copies.</p>
 * <p><strong>Observability:</strong> No direct metrics; callers may log decoding failures.</p>
 *
 * @since 0.1.0
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
   *
   * <p><strong>Concurrency:</strong> Thread-safe static method.</p>
   * <p><strong>Performance:</strong> Bounds checks avoid {@link ArrayIndexOutOfBoundsException} and reuse JVM codecs.</p>
   * <p><strong>Observability:</strong> Callers should log when empty results indicate truncated data.</p>
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
   *
   * <p><strong>Concurrency:</strong> Uses a read-only view; safe for callers that share the buffer.</p>
   * <p><strong>Performance:</strong> Relies on {@link ByteBuffer#asReadOnlyBuffer()} to avoid copying data.</p>
   * <p><strong>Observability:</strong> Callers may log empty strings when no bytes remain.</p>
   */
  public static String decode(ByteBuffer buffer) {
    if (buffer == null || !buffer.hasRemaining()) {
      return "";
    }
    ByteBuffer readOnly = buffer.asReadOnlyBuffer();
    return StandardCharsets.UTF_8.decode(readOnly).toString();
  }
}
