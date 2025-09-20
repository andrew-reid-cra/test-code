package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

/**
 * Placeholder for the historical assembler implementation that referred to legacy sinks.
 * The modern RADAR pipeline no longer depends on this class; attempting to use it will
 * immediately fail.
 */
@Deprecated
public final class LegacyHttpAssembler {
  public LegacyHttpAssembler(Object... ignored) {
    throw new UnsupportedOperationException("Legacy assembler is not supported; use the new pipeline");
  }

  public void onTcpSegment(
      long timestampMicros,
      String src,
      int srcPort,
      String dst,
      int dstPort,
      long sequence,
      boolean fromClient,
      byte[] payload,
      int offset,
      int length,
      boolean fin) {
    throw new UnsupportedOperationException("Legacy assembler is not supported; use the new pipeline");
  }
}