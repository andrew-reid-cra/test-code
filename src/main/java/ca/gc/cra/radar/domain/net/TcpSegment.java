package ca.gc.cra.radar.domain.net;

/** Simplified TCP segment abstraction exposed by the flow assembler. */
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

  public TcpSegment {
    payload = payload != null ? payload.clone() : new byte[0];
  }
}
