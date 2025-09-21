package ca.gc.cra.radar.domain.net;

/**
 * Immutable representation of a captured link-layer frame.
 *
 * @param data raw frame bytes; defensively copied
 * @param timestampMicros capture timestamp in microseconds since epoch
 * @since RADAR 0.1-doc
 */
public record RawFrame(byte[] data, long timestampMicros) {
  /**
   * Creates a raw frame snapshot while defensively copying payload data.
   *
   * @since RADAR 0.1-doc
   */
  public RawFrame {
    data = data != null ? data.clone() : new byte[0];
  }
}
