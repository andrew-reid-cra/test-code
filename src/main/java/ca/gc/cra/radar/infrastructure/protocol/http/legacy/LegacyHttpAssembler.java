package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

/**
 * Placeholder for the historical assembler implementation that referred to legacy sinks.
 * The modern RADAR pipeline no longer depends on this class; attempting to use it will
 * immediately fail.
 */
@Deprecated
public final class LegacyHttpAssembler {
  /**
   * Legacy hook invoked for each TCP segment; always throws {@link UnsupportedOperationException}.
   *
   * @param timestampMicros capture timestamp in microseconds
   * @param src source IP address
   * @param srcPort source TCP port
   * @param dst destination IP address
   * @param dstPort destination TCP port
   * @param sequence TCP sequence number
   * @param fromClient {@code true} when the segment originated from the client
   * @param payload TCP payload bytes
   * @param offset payload offset within the array
   * @param length number of bytes provided in {@code payload}
   * @param fin whether the FIN flag was set
   * @throws UnsupportedOperationException always thrown to signal unsupported usage
   * @since RADAR 0.1-doc
   */
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


