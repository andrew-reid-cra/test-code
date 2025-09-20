package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.application.port.FrameDecoder;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import java.util.Objects;
import java.util.Optional;

/**
 * Capture pipeline that writes TCP segments to persistent storage using the new port abstractions.
 */
public final class SegmentCaptureUseCase {
  private final PacketSource packetSource;
  private final FrameDecoder frameDecoder;
  private final SegmentPersistencePort persistence;
  private final MetricsPort metrics;

  public SegmentCaptureUseCase(
      PacketSource packetSource,
      FrameDecoder frameDecoder,
      SegmentPersistencePort persistence,
      MetricsPort metrics) {
    this.packetSource = Objects.requireNonNull(packetSource, "packetSource");
    this.frameDecoder = Objects.requireNonNull(frameDecoder, "frameDecoder");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  public void run() throws Exception {
    boolean started = false;
    try {
      packetSource.start();
      started = true;

      while (!Thread.currentThread().isInterrupted()) {
        RawFrame frame;
        try {
          Optional<RawFrame> maybeFrame = packetSource.poll();
          if (maybeFrame.isEmpty()) {
            continue;
          }
          frame = maybeFrame.get();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          break;
        }

        Optional<TcpSegment> maybeSegment = frameDecoder.decode(frame);
        if (maybeSegment.isEmpty()) {
          metrics.increment("capture.segment.skipped.decode");
          continue;
        }

        TcpSegment segment = maybeSegment.get();
        if (isPureAck(segment)) {
          metrics.increment("capture.segment.skipped.pureAck");
          continue;
        }

        SegmentRecord record = toRecord(frame.timestampMicros(), segment);
        persistence.persist(record);
        metrics.increment("capture.segment.persisted");
      }
    } finally {
      try {
        persistence.flush();
      } finally {
        persistence.close();
        if (started) {
          packetSource.close();
        }
      }
    }
  }

  private static boolean isPureAck(TcpSegment segment) {
    return segment.payload().length == 0
        && segment.ack()
        && !segment.syn()
        && !segment.fin()
        && !segment.rst()
        && !segment.psh();
  }

  private static SegmentRecord toRecord(long timestampMicros, TcpSegment segment) {
    FiveTuple flow = segment.flow();
    int flags = 0;
    if (segment.fin()) flags |= SegmentRecord.FIN;
    if (segment.syn()) flags |= SegmentRecord.SYN;
    if (segment.rst()) flags |= SegmentRecord.RST;
    if (segment.psh()) flags |= SegmentRecord.PSH;
    if (segment.ack()) flags |= SegmentRecord.ACK;

    return new SegmentRecord(
        timestampMicros,
        flow.srcIp(),
        flow.srcPort(),
        flow.dstIp(),
        flow.dstPort(),
        segment.sequenceNumber(),
        flags,
        segment.payload());
  }
}
