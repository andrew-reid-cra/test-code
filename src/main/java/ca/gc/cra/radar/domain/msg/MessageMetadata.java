package ca.gc.cra.radar.domain.msg;

import java.util.Map;

/**
 * Metadata attached to protocol messages, including transaction correlation identifiers.
 *
 * @param transactionId identifier used to correlate related messages; may be {@code null}
 * @param attributes arbitrary key/value pairs (e.g., headers, TN3270 fields); caller should supply an immutable map
 * @since RADAR 0.1-doc
 */
public record MessageMetadata(String transactionId, Map<String, String> attributes) {
  /**
   * Returns an empty metadata instance with no attributes.
   *
   * @return metadata containing no values
   * @since RADAR 0.1-doc
   */
  public static MessageMetadata empty() {
    return new MessageMetadata(null, Map.of());
  }
}
