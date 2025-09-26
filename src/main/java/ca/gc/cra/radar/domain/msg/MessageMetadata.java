package ca.gc.cra.radar.domain.msg;

import java.util.Map;

/**
 * <strong>What:</strong> Metadata attached to protocol messages, including transaction correlation identifiers.
 * <p><strong>Why:</strong> Carries structured attributes such as HTTP headers or TN3270 field metadata into sinks.</p>
 * <p><strong>Role:</strong> Domain value object referenced by {@link MessageEvent} and {@link MessagePair}.</p>
 * <p><strong>Thread-safety:</strong> Record is immutable; callers should supply immutable attribute maps.</p>
 * <p><strong>Performance:</strong> Holds references without copying; callers control attribute map size.</p>
 * <p><strong>Observability:</strong> Attributes may be exported as metric/log tags.</p>
 *
 * @param transactionId identifier used to correlate related messages; may be {@code null}
 * @param attributes arbitrary key/value pairs (e.g., headers, TN3270 fields); caller should supply an immutable map
 * @since 0.1.0
 */
public record MessageMetadata(String transactionId, Map<String, String> attributes) {
  /**
   * Returns an empty metadata instance with no attributes.
   *
   * @return metadata containing no values
   *
   * <p><strong>Concurrency:</strong> Safe to share across threads.</p>
   * <p><strong>Performance:</strong> Returns a singleton-like record without additional allocations.</p>
   * <p><strong>Observability:</strong> Indicates absence of metadata for downstream sinks.</p>
   */
  public static MessageMetadata empty() {
    return new MessageMetadata(null, Map.of());
  }
}
