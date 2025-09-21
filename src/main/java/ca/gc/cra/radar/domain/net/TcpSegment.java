package ca.gc.cra.radar.domain.net;

/**
 * Simplified TCP segment abstraction exposed by the flow assembler.
 *
 * @param flow five-tuple describing the connection
 * @param sequenceNumber TCP sequence number for the first payload byte
 * @param fromClient {@code true} when oriented client-to-server
 * @param payload TCP payload bytes; defensively copied
 * @param fin whether the FIN flag is set
 * @param syn whether the SYN flag is set
 * @param rst whether the RST flag is set
 * @param psh whether the PSH flag is set
 * @param ack whether the ACK flag is set
 * @param timestampMicros capture timestamp in microseconds since epoch
 * @since RADAR 0.1-doc
 */
public record TcpSegment(
    FiveTuple flow,
    long sequenceNumber,
    boolean fromClient,
    byte[] payload,
    boolean fin,
    boolean syn,
    boolean rst,
    boolean psh,
    boolean ack,
    long timestampMicros) {

  /**
   * Normalizes the TCP payload array to maintain immutability.
   *
   * @since RADAR 0.1-doc
   */
  public TcpSegment {
    payload = payload != null ? payload.clone() : new byte[0];
  }
}
