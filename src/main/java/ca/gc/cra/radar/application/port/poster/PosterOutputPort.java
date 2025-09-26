package ca.gc.cra.radar.application.port.poster;

import ca.gc.cra.radar.domain.protocol.ProtocolId;

/**
 * <strong>What:</strong> Output port for poster pipelines that emit rendered protocol transcripts.
 * <p><strong>Why:</strong> Decouples poster rendering from delivery so adapters can target files, HTTP, or messaging systems.</p>
 * <p><strong>Role:</strong> Domain port implemented by sink adapters.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Persist or stream rendered poster reports.</li>
 *   <li>Honor lifecycle and flushing semantics required by the sink.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations document concurrency; most expect single-threaded writes.</p>
 * <p><strong>Performance:</strong> Should support streaming output and bounded buffering.</p>
 * <p><strong>Observability:</strong> Implementations emit {@code poster.output.*} metrics for write throughput and errors.</p>
 *
 * @implNote Extends {@link AutoCloseable} so callers can flush and release resources deterministically.
 * @since 0.1.0
 */
public interface PosterOutputPort extends AutoCloseable {
  /**
   * Writes a rendered poster report to the downstream sink.
   *
   * @param report poster payload containing protocol metadata and content; must not be {@code null}
   * @throws Exception if writing fails or the sink rejects the payload
   *
   * <p><strong>Concurrency:</strong> Follow the implementation's threading contract; typically single-threaded.</p>
   * <p><strong>Performance:</strong> Implementations may batch writes; method should avoid long blocking operations.</p>
   * <p><strong>Observability:</strong> Callers should record metrics (e.g., {@code poster.output.written}).</p>
   */
  void write(PosterReport report) throws Exception;

  /**
   * Closes the output port and flushes buffered data.
   *
   * @throws Exception if shutdown fails
   *
   * <p><strong>Concurrency:</strong> Call after all writes finish; not thread-safe.</p>
   * <p><strong>Performance:</strong> May block while flushing final payloads.</p>
   * <p><strong>Observability:</strong> Implementations should log completion and emit closure metrics.</p>
   */
  @Override
  default void close() throws Exception {}

  /**
   * <strong>What:</strong> Immutable representation of a rendered poster output.
   * <p><strong>Why:</strong> Carries protocol metadata and textual content to sink adapters.</p>
   * <p><strong>Role:</strong> Domain value object transported via {@link PosterOutputPort#write(PosterReport)}.</p>
   * <p><strong>Thread-safety:</strong> Record is immutable and safe to share.</p>
   * <p><strong>Performance:</strong> Lightweight wrapper; content length dominates memory usage.</p>
   * <p><strong>Observability:</strong> Fields map to poster metrics tags (e.g., protocol, transaction ID).</p>
   *
   * @param protocol protocol associated with the rendered poster
   * @param txId transaction identifier for correlation; must be non-blank
   * @param timestampMicros wall-clock timestamp in microseconds when the poster was produced
   * @param content rendered poster payload
   * @implNote Constructor validates non-null protocol and content plus non-blank transaction IDs.
   * @since 0.1.0
   */
  record PosterReport(ProtocolId protocol, String txId, long timestampMicros, String content) {
    /**
     * Validates poster report invariants.
     *
     * @throws IllegalArgumentException if required fields are missing
     *
     * <p><strong>Concurrency:</strong> Record construction is thread-safe.</p>
     * <p><strong>Performance:</strong> Validation is O(1).</p>
     * <p><strong>Observability:</strong> Does not emit metrics; callers log validation failures.</p>
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

