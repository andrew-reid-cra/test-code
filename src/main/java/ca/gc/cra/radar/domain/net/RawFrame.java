package ca.gc.cra.radar.domain.net;

import java.util.Arrays;

/**
 * <strong>What:</strong> Immutable representation of a captured link-layer frame.
 * <p><strong>Why:</strong> Allows capture adapters to hand off raw frames to decoders without exposing mutable buffers.</p>
 * <p><strong>Role:</strong> Domain value object bridging capture and frame decoding.</p>
 * <p><strong>Thread-safety:</strong> Immutable; safe for concurrent sharing.</p>
 * <p><strong>Performance:</strong> Clones payload data to preserve isolation; callers control when cloning occurs.</p>
 * <p><strong>Observability:</strong> Timestamp fields inform metrics such as {@code capture.frame.timestamp}.</p>
 *
 * @param data raw frame bytes; defensively copied
 * @param timestampMicros capture timestamp in microseconds since epoch
 * @since 0.1.0
 */
public record RawFrame(byte[] data, long timestampMicros) {
  /**
   * Creates a raw frame snapshot while defensively copying payload data.
   *
   * <p><strong>Concurrency:</strong> Result is immutable.</p>
   * <p><strong>Performance:</strong> Copies the byte array when provided; substitutes an empty array otherwise.</p>
   * <p><strong>Observability:</strong> Timestamp remains unchanged for downstream logging.</p>
   */
  public RawFrame {
    data = data != null ? data.clone() : new byte[0];
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RawFrame that)) {
      return false;
    }
    return timestampMicros == that.timestampMicros()
        && Arrays.equals(data, that.data());
  }

  @Override
  public int hashCode() {
    int result = Arrays.hashCode(data);
    result = 31 * result + Long.hashCode(timestampMicros);
    return result;
  }

  @Override
  public String toString() {
    return "RawFrame{"
        + "data=" + Arrays.toString(data)
        + ", timestampMicros=" + timestampMicros
        + '}';
  }
}
