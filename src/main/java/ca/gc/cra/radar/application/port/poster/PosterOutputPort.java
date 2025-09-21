package ca.gc.cra.radar.application.port.poster;

import ca.gc.cra.radar.domain.protocol.ProtocolId;

/**
 * Output port for poster pipelines that emit rendered protocol transcripts.
 *
 * @since RADAR 0.1-doc
 */
public interface PosterOutputPort extends AutoCloseable {
  /**
   * Writes a rendered poster report to the downstream sink.
   *
   * @param report poster payload containing protocol metadata and content
   * @throws Exception if writing fails
   * @since RADAR 0.1-doc
   */
  void write(PosterReport report) throws Exception;

  /**
   * Closes the output port.
   *
   * @throws Exception if shutdown fails
   * @since RADAR 0.1-doc
   */
  @Override
  default void close() throws Exception {}

  /**
   * Immutable representation of a rendered poster output.
   *
   * @param protocol protocol associated with the rendered transaction
   * @param txId stable identifier for the transaction/message pair
   * @param timestampMicros emission timestamp in microseconds since epoch
   * @param content rendered content (e.g., HTML, plain text)
   * @since RADAR 0.1-doc
   */
  record PosterReport(ProtocolId protocol, String txId, long timestampMicros, String content) {
    /**
     * Validates poster report invariants.
     *
     * @since RADAR 0.1-doc
     */
    public PosterReport {
      if (protocol == null) {
        throw new IllegalArgumentException("protocol must not be null");
      }
      if (txId == null || txId.isBlank()) {
        throw new IllegalArgumentException("txId must not be blank");
      }
      if (content == null) {
        throw new IllegalArgumentException("content must not be null");
      }
    }
  }
}
