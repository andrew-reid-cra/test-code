package ca.gc.cra.radar.logging;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Logging hygiene helpers to prevent accidental leakage of large or sensitive payloads.
 */
public final class Logs {
  private static final String NULL_PLACEHOLDER = "<null>";
  private static final String REDACTED_PLACEHOLDER = "[REDACTED]";

  private Logs() {
    // Utility
  }

  /**
   * Truncates a string to the requested byte length, appending a marker with original length.
   *
   * @param value string to truncate
   * @param maxBytes maximum number of bytes to retain
   * @return truncated string when necessary; original string otherwise
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
   * @param value ignored original value
   * @return redacted placeholder
   */
  public static String redact(String value) {
    return REDACTED_PLACEHOLDER;
  }
}
