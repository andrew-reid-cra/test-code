package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.capture.SegmentRecord;

/**
 * <strong>What:</strong> Domain port for persisting captured TCP segment records prior to assembly.
 * <p><strong>Why:</strong> Enables capture pipelines to archive raw segments for replay, auditing, or cold-path processing.</p>
 * <p><strong>Role:</strong> Sink-side output port serving the capture stage.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Persist {@link SegmentRecord} entries in capture order.</li>
 *   <li>Flush buffered segments when requested.</li>
 *   <li>Dispose of persistence resources cleanly.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations must document concurrency; capture may run multi-threaded.</p>
 * <p><strong>Performance:</strong> Hot path for capture; adapters should batch writes and reuse byte buffers.</p>
 * <p><strong>Observability:</strong> Implementations should emit {@code capture.segment.persist.*} metrics and structured logs.</p>
 *
 * @since 0.1.0
 * @see PersistencePort
 */
public interface SegmentPersistencePort extends AutoCloseable {
  /**
   * Persists a captured segment record.
   *
   * @param record segment metadata and payload; must not be {@code null}
   * @throws Exception if persistence fails or the underlying store rejects the write
   *
   * <p><strong>Concurrency:</strong> Callers may invoke concurrently if the implementation supports it.</p>
   * <p><strong>Performance:</strong> Expected to be O(1) aside from sink latency.</p>
   * <p><strong>Observability:</strong> Implementations should record counters such as {@code capture.segment.persisted}.</p>
   */
  void persist(SegmentRecord record) throws Exception;

  /**
   * Flushes buffered records to durable storage.
   *
   * @throws Exception if flushing fails
   *
   * <p><strong>Concurrency:</strong> Invoke when no concurrent {@link #persist(SegmentRecord)} calls are active unless specified.</p>
   * <p><strong>Performance:</strong> May block while draining queues.</p>
   * <p><strong>Observability:</strong> Emit flush duration metrics when available.</p>
   */
  default void flush() throws Exception {}

  /**
   * Closes the persistence resource.
   *
   * @throws Exception if shutdown fails
   *
   * <p><strong>Concurrency:</strong> Call once during teardown.</p>
   * <p><strong>Performance:</strong> May block while flushing remaining records.</p>
   * <p><strong>Observability:</strong> Implementations should log closure outcomes.</p>
   */
  @Override
  default void close() throws Exception {}
}
