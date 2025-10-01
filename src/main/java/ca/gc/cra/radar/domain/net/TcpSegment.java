package ca.gc.cra.radar.domain.net;

import java.util.Arrays;
import java.util.Objects;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * <strong>What:</strong> Simplified TCP segment abstraction exposed by the flow assembler.
 * <p><strong>Why:</strong> Provides the assemble stage with immutable segment metadata and payload for ordering.</p>
 * <p><strong>Role:</strong> Domain value object bridging frame decoding to flow assembly.</p>
 * <p><strong>Thread-safety:</strong> Immutable; safe for sharing across threads.</p>
 * <p><strong>Performance:</strong> Clones payload data to avoid external mutation; flags retain original TCP semantics.</p>
 * <p><strong>Observability:</strong> Fields support metrics like {@code assemble.segment.flags}.</p>
 *
 * @param flow five-tuple describing the connection; never {@code null}
 * @param sequenceNumber TCP sequence number for the first payload byte
 * @param fromClient {@code true} when oriented client-to-server
 * @param payload TCP payload bytes; defensively copied
 * @param fin whether the FIN flag is set
 * @param syn whether the SYN flag is set
 * @param rst whether the RST flag is set
 * @param psh whether the PSH flag is set
 * @param ack whether the ACK flag is set
 * @param timestampMicros capture timestamp in microseconds since epoch
 * @since 0.1.0
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
   * <p><strong>Concurrency:</strong> Result is immutable.</p>
   * <p><strong>Performance:</strong> Copies payload when provided; substitutes an empty array otherwise.</p>
   * <p><strong>Observability:</strong> Timestamp remains unchanged for downstream metrics.</p>
   */
  public TcpSegment {
    payload = payload != null ? payload.clone() : new byte[0];
  }

  /**
   * Provides access to the normalized payload without additional copying.
   *
   * @return internal payload array; callers must not mutate after passing to the domain model
   */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "TCP payload is defensively copied on construction; exposing the canonical buffer avoids extra allocations per segment.")
  public byte[] payload() {
    return payload;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TcpSegment(
        FiveTuple otherFlow,
        long otherSequenceNumber,
        boolean otherFromClient,
        byte[] otherPayload,
        boolean otherFin,
        boolean otherSyn,
        boolean otherRst,
        boolean otherPsh,
        boolean otherAck,
        long otherTimestamp))) {
      return false;
    }
    return fromClient == otherFromClient
        && fin == otherFin
        && syn == otherSyn
        && rst == otherRst
        && psh == otherPsh
        && ack == otherAck
        && timestampMicros == otherTimestamp
        && Objects.equals(flow, otherFlow)
        && sequenceNumber == otherSequenceNumber
        && Arrays.equals(payload, otherPayload);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(flow, sequenceNumber, fromClient, fin, syn, rst, psh, ack, timestampMicros);
    result = 31 * result + Arrays.hashCode(payload);
    return result;
  }

  @Override
  public String toString() {
    return "TcpSegment{"
        + "flow=" + flow
        + ", sequenceNumber=" + sequenceNumber
        + ", fromClient=" + fromClient
        + ", payload=" + Arrays.toString(payload)
        + ", fin=" + fin
        + ", syn=" + syn
        + ", rst=" + rst
        + ", psh=" + psh
        + ", ack=" + ack
        + ", timestampMicros=" + timestampMicros
        + '}';
  }
}
