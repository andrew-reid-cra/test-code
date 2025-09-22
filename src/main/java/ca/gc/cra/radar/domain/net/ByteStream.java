package ca.gc.cra.radar.domain.net;

/**
 * Ordered slice of TCP bytes emitted by a flow assembler for a single direction.
 *
 * @param flow flow identifier for the segment
 * @param fromClient {@code true} when the bytes originated from the client
 * @param data contiguous payload bytes; ownership is transferred to the {@link ByteStream}
 * @param timestampMicros timestamp associated with the latest contributing segment (microseconds)
 * @since RADAR 0.1-doc
 */
public record ByteStream(FiveTuple flow, boolean fromClient, byte[] data, long timestampMicros) {
  /**
   * Creates a byte stream record without copying payload data.
   *
   * @since RADAR 0.1-doc
   */
  public ByteStream {
    data = data != null ? data : new byte[0];
  }
}
