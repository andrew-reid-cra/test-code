package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.time.Duration;
import java.util.Objects;

/**
 * <strong>What:</strong> Adapter that exposes {@link KafkaSegmentReader} as an {@link AssembleUseCase.SegmentRecordReader}.
 * <p><strong>Why:</strong> Lets assemble pipelines consume capture segments from Kafka without binding directly to the consumer API.</p>
 * <p><strong>Role:</strong> Infrastructure adapter translating messaging semantics into blocking reads.</p>
 * <p><strong>Thread-safety:</strong> Not thread-safe; use one instance per assemble worker thread.</p>
 * <p><strong>Performance:</strong> Polls Kafka at a configurable interval (default 200 ms) to balance latency and load.</p>
 * <p><strong>Observability:</strong> Relies on the underlying reader for metrics/logging.</p>
 *
 * @since 0.1.0
 */
public final class KafkaSegmentRecordReaderAdapter implements AssembleUseCase.SegmentRecordReader {
  private static final Duration DEFAULT_POLL = Duration.ofMillis(200);

  private final KafkaSegmentReader delegate;
  private final Duration pollInterval;

  /**
   * Creates an adapter with the default poll interval (200 ms).
   *
   * @param delegate underlying Kafka segment reader; must not be {@code null}
   * @throws NullPointerException if {@code delegate} is {@code null}
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread.</p>
   * <p><strong>Performance:</strong> Uses a 200&nbsp;ms poll interval balancing latency and batching.</p>
   * <p><strong>Observability:</strong> Metrics/logging flow through the delegate.</p>
   */
  public KafkaSegmentRecordReaderAdapter(KafkaSegmentReader delegate) {
    this(delegate, DEFAULT_POLL);
  }

  /**
   * Creates an adapter with a custom poll interval.
   *
   * @param delegate underlying Kafka segment reader; must not be {@code null}
   * @param pollInterval poll interval to use when waiting for Kafka records; must not be {@code null}
   * @throws NullPointerException if {@code delegate} or {@code pollInterval} is {@code null}
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread.</p>
   * <p><strong>Performance:</strong> Poll interval controls latency versus broker load.</p>
   * <p><strong>Observability:</strong> Use shorter intervals when latency metrics need tighter bounds.</p>
   */
  public KafkaSegmentRecordReaderAdapter(KafkaSegmentReader delegate, Duration pollInterval) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
  }

  /**
   * Blocks until a segment is available or the calling thread is interrupted.
   *
   * @return next segment record, or {@code null} if the thread is interrupted
   * @throws Exception if the underlying reader fails
   *
   * <p><strong>Concurrency:</strong> Intended for single-threaded use.</p>
   * <p><strong>Performance:</strong> Polls Kafka at {@link #pollInterval}; loop continues until data arrives or interruption occurs.</p>
   * <p><strong>Observability:</strong> Delegates metrics/logging to the {@link KafkaSegmentReader}; callers may count timeouts.</p>
   */
  @Override
  public SegmentRecord next() throws Exception {
    while (!Thread.currentThread().isInterrupted()) {
      var maybeRecord = delegate.poll(pollInterval);
      if (maybeRecord.isPresent()) {
        return maybeRecord.get();
      }
    }
    return null;
  }

  /**
   * Closes the underlying Kafka reader.
   *
   * <p><strong>Concurrency:</strong> Invoke during shutdown; not thread-safe.</p>
   * <p><strong>Performance:</strong> Delegates to {@link KafkaSegmentReader#close()}.</p>
   * <p><strong>Observability:</strong> Allows higher layers to log completion events.</p>
   */
  @Override
  public void close() {
    delegate.close();
  }
}

