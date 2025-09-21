package ca.gc.cra.radar.infrastructure.protocol.http.legacy;

/**
 * Legacy entry point for the retired HTTP assembler pipeline.
 * <p>This stub throws to guard deployments that still reference the pre-0.1 assembler wiring.</p>
 * <p>Replace invocations with the modern pipeline, for example:</p>
 * <pre>{@code
 * PosterUseCase poster = new PosterUseCase();
 * poster.run(posterConfig);
 * }</pre>
 *
 * @deprecated since RADAR 0.1.0; scheduled for removal in RADAR 0.2.0. Use
 *     {@link ca.gc.cra.radar.application.pipeline.PosterUseCase} or the protocol-specific
 *     {@link ca.gc.cra.radar.application.port.poster.PosterPipeline} implementations instead.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
public final class LegacyHttpAssembler {
  /**
   * Retained only for binary compatibility; always throws
   * {@link UnsupportedOperationException}.
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
   * @deprecated since RADAR 0.1.0; invoke the current assemble/poster pipeline instead
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


