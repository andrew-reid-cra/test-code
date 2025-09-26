package ca.gc.cra.radar.application.port;

import ca.gc.cra.radar.domain.msg.MessagePair;

/**
 * <strong>What:</strong> Domain port for persisting reconstructed protocol message pairs.
 * <p><strong>Why:</strong> Allows assemble pipelines to emit request/response pairs to durable sinks without binding to a vendor API.</p>
 * <p><strong>Role:</strong> Output port on the sink side of RADAR's hexagonal architecture.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Persist message pairs in arrival order.</li>
 *   <li>Flush buffered data so capture pipelines can enforce durability guarantees.</li>
 *   <li>Release underlying resources cleanly during shutdown.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Implementations document their guarantees; pipelines may invoke from multiple threads.</p>
 * <p><strong>Performance:</strong> Hot path for sink throughput; adapters should batch writes and reuse buffers.</p>
 * <p><strong>Observability:</strong> Implementations should emit {@code sink.*} metrics and structured logs for failures.</p>
 *
 * @since 0.1.0
 * @see SegmentPersistencePort
 */
public interface PersistencePort extends AutoCloseable {
  /**
   * Persists a single message pair.
   *
   * @param pair reconstructed request/response pair; may be {@code null} when the pipeline emits tombstones
   * @throws Exception if the underlying sink rejects the write or encounters an IO error
   *
   * <p><strong>Concurrency:</strong> Callers may invoke concurrently when the implementation is thread-safe.</p>
   * <p><strong>Performance:</strong> Expected to be O(1) aside from sink latency; adapters may enqueue asynchronously.</p>
   * <p><strong>Observability:</strong> Implementations typically increment counters such as {@code sink.persist.success}.</p>
   */
  void persist(MessagePair pair) throws Exception;

  /**
   * Flushes buffered state to the underlying store.
   *
   * @throws Exception if flushing fails or the sink signals a fatal error
   *
   * <p><strong>Concurrency:</strong> Call when no concurrent {@link #persist(MessagePair)} calls are in flight unless documented.</p>
   * <p><strong>Performance:</strong> May block until buffers drain.</p>
   * <p><strong>Observability:</strong> Implementations should log slow flushes and emit {@code sink.flush.duration} metrics.</p>
   */
  default void flush() throws Exception {}

  /**
   * Closes the persistence resource.
   *
   * @throws Exception if shutdown fails or pending writes cannot complete
   *
   * <p><strong>Concurrency:</strong> Invoke once during teardown.</p>
   * <p><strong>Performance:</strong> May block while flushing remaining data.</p>
   * <p><strong>Observability:</strong> Implementations should log closure outcomes and emit shutdown metrics.</p>
   */
  @Override
  default void close() throws Exception {}
}
