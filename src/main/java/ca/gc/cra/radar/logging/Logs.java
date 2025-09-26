package ca.gc.cra.radar.logging;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * <strong>What:</strong> Logging hygiene helpers that minimize sensitive payload exposure.
 * <p><strong>Why:</strong> Prevents overly large frame dumps and secrets from leaking into operator logs.
 * <p><strong>Role:</strong> Cross-cutting adapter utility used throughout capture and sink pipelines.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Truncate UTF-8 payloads to a safe byte budget while preserving readability.</li>
 *   <li>Provide consistent redaction placeholders for sensitive fields.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Stateless utilities safe for concurrent use.</p>
 * <p><strong>Performance:</strong> Truncation allocates transient buffers proportional to {@code maxBytes}.</p>
 * <p><strong>Observability:</strong> Indirectly shapes log messages; emits no metrics.</p>
 *
 * @implNote Decoding uses {@link CodingErrorAction#IGNORE} to avoid exceptions when truncating mid-codepoint.
 * @since 0.1.0
 * @see LoggingConfigurator
 */
public final class Logs {
  private static final String NULL_PLACEHOLDER = "<null>";
  private static final String REDACTED_PLACEHOLDER = "[REDACTED]";

  private Logs() {
    // Utility
  }

  /**
   * Truncates a string to the requested UTF-8 byte length, appending the original length metadata.
   *
   * @param value string to truncate; {@code null} results in {@code "<null>"}
   * @param maxBytes maximum number of bytes to retain; must be positive
   * @return truncated string when the input exceeds {@code maxBytes}; otherwise the original value
   * @throws IllegalArgumentException if {@code maxBytes} is not positive
   *
   * <p><strong>Concurrency:</strong> Safe to invoke from multiple threads; no shared state.</p>
   * <p><strong>Performance:</strong> Allocates at most {@code maxBytes} bytes for the decoder buffer.</p>
   * <p><strong>Observability:</strong> Adds {@code "? (truncated, X of Y)"} suffix to flag shortened payloads.</p>
   */
  public static String truncate(String value, int maxBytes) {
    if (value == null) {
      return NULL_PLACEHOLDER;
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) {
      return value;
    }
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.IGNORE)
        .onUnmappableCharacter(CodingErrorAction.IGNORE);
    try {
      CharBuffer buffer = decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes));
      String truncated = buffer.toString();
      return truncated + "? (truncated, " + maxBytes + " of " + bytes.length + ")";
    } catch (CharacterCodingException ex) {
      // Fallback to defensive substring on unexpected decoder issues.
      String utf16Safe = new String(bytes, 0, Math.min(bytes.length, maxBytes), StandardCharsets.UTF_8);
      return utf16Safe + "? (truncated)";
    }
  }

  /**
   * Returns a standard redacted placeholder for sensitive content.
   *
   * @param value ignored original value; retained for fluent API usage
   * @return the redacted placeholder string
   *
   * <p><strong>Concurrency:</strong> Safe for concurrent use.</p>
   * <p><strong>Performance:</strong> Constant-time string literal return.</p>
   * <p><strong>Observability:</strong> Ensures logs explicitly indicate redaction.</p>
   */
  public static String redact(String value) {
    return REDACTED_PLACEHOLDER;
  }
}
