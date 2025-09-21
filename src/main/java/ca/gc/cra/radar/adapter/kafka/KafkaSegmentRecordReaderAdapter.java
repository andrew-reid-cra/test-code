package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.time.Duration;
import java.util.Objects;

/**
 * Bridges {@link KafkaSegmentReader} to the assemble use-case segment reader contract.
 * <p>Not thread-safe; intended for use by a single Assemble pipeline worker.
 *
 * @since RADAR 0.1-doc
 */
public final class KafkaSegmentRecordReaderAdapter implements AssembleUseCase.SegmentRecordReader {
  private static final Duration DEFAULT_POLL = Duration.ofMillis(200);

  private final KafkaSegmentReader delegate;
  private final Duration pollInterval;

  /**
   * Creates an adapter with the default poll interval (200 ms).
   *
   * @param delegate underlying Kafka segment reader
   * @throws NullPointerException if {@code delegate} is {@code null}
   * @since RADAR 0.1-doc
   */
  public KafkaSegmentRecordReaderAdapter(KafkaSegmentReader delegate) {
    this(delegate, DEFAULT_POLL);
  }

  /**
   * Creates an adapter with a custom poll interval.
   *
   * @param delegate underlying Kafka segment reader
   * @param pollInterval poll interval to use when waiting for Kafka records
   * @throws NullPointerException if {@code delegate} or {@code pollInterval} is {@code null}
   * @since RADAR 0.1-doc
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
   * @implNote Delegates polling to {@link KafkaSegmentReader#poll(Duration)} in a loop.
   * @since RADAR 0.1-doc
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
   * @since RADAR 0.1-doc
   */
  @Override
  public void close() {
    delegate.close();
  }
}

