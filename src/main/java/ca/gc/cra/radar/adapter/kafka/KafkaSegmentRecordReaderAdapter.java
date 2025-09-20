package ca.gc.cra.radar.adapter.kafka;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import java.time.Duration;
import java.util.Objects;

/** Bridges {@link KafkaSegmentReader} to the assemble use-case reader contract. */
public final class KafkaSegmentRecordReaderAdapter implements AssembleUseCase.SegmentRecordReader {
  private static final Duration DEFAULT_POLL = Duration.ofMillis(200);

  private final KafkaSegmentReader delegate;
  private final Duration pollInterval;

  public KafkaSegmentRecordReaderAdapter(KafkaSegmentReader delegate) {
    this(delegate, DEFAULT_POLL);
  }

  public KafkaSegmentRecordReaderAdapter(KafkaSegmentReader delegate, Duration pollInterval) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
  }

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

  @Override
  public void close() {
    delegate.close();
  }
}
