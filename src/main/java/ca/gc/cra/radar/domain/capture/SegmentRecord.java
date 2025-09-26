package ca.gc.cra.radar.domain.capture;

/**
 * <strong>What:</strong> Immutable representation of a captured TCP segment persisted by RADAR.
 * <p><strong>Why:</strong> Allows capture pipelines to archive raw network data for replay and auditing.</p>
 * <p><strong>Role:</strong> Domain value passed to {@link ca.gc.cra.radar.application.port.SegmentPersistencePort}.</p>
 * <p><strong>Thread-safety:</strong> Immutable; safe across threads.</p>
 * <p><strong>Performance:</strong> Clones payload once on construction to preserve ownership.</p>
 * <p><strong>Observability:</strong> Fields provide metrics tags (e.g., {@code capture.segment.flags}).</p>
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
 * @since 0.1.0
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
   * <p><strong>Concurrency:</strong> Result is immutable.</p>
   * <p><strong>Performance:</strong> Clones payload when provided; uses empty array otherwise.</p>
   * <p><strong>Observability:</strong> Timestamp remains unchanged for downstream metrics.</p>
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
