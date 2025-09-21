package ca.gc.cra.radar.domain.capture;

/**
 * Immutable representation of a captured TCP segment persisted by RADAR.
 *
 * @param timestampMicros capture timestamp in microseconds since epoch
 * @param srcIp source IP address (IPv4/IPv6 string)
 * @param srcPort source TCP port
 * @param dstIp destination IP address
 * @param dstPort destination TCP port
 * @param sequence TCP sequence number as observed on the wire
 * @param flags bitmask of TCP control flags (see constants)
 * @param payload captured TCP payload bytes; defensively copied
 * @implNote The payload array is cloned during construction to preserve immutability.
 * @since RADAR 0.1-doc
 */
public record SegmentRecord(
    long timestampMicros,
    String srcIp,
    int srcPort,
    String dstIp,
    int dstPort,
    long sequence,
    int flags,
    byte[] payload) {
  /**
   * Normalizes the segment payload and ensures null-safe arrays.
   *
   * @since RADAR 0.1-doc
   */
  public SegmentRecord {
    payload = payload != null ? payload.clone() : new byte[0];
  }

  /** TCP FIN flag mask. */
  public static final int FIN = 1;
  /** TCP SYN flag mask. */
  public static final int SYN = 2;
  /** TCP RST flag mask. */
  public static final int RST = 4;
  /** TCP PSH flag mask. */
  public static final int PSH = 8;
  /** TCP ACK flag mask. */
  public static final int ACK = 16;
}
