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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Coordinates packet capture by polling a {@link PacketSource}, decoding frames, and persisting
 * TCP segments.
 * <p>Not thread-safe; expected to run once per process. Filters pure ACKs to reduce storage
 * volume.</p>
 *
 * @implNote Uses {@link SegmentPersistencePort#persist(SegmentRecord)} for each accepted segment
 * and increments metrics counters for observability.
 * @see ca.gc.cra.radar.application.port.SegmentPersistencePort
 * @since RADAR 0.1-doc
 */
public final class SegmentCaptureUseCase {
  private static final Logger log = LoggerFactory.getLogger(SegmentCaptureUseCase.class);

  private final PacketSource packetSource;
  private final FrameDecoder frameDecoder;
  private final SegmentPersistencePort persistence;
  private final MetricsPort metrics;

  /**
   * Creates a capture use case with the supplied dependencies.
   *
   * @param packetSource source of raw frames
   * @param frameDecoder decoder that maps frames to TCP segments
   * @param persistence sink for persisted segments
   * @param metrics metrics sink for capture counters
   */
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

  /**
   * Runs the capture loop until interrupted, persisting each non-empty TCP segment.
   *
   * @throws Exception if any port fails during capture, persistence, or cleanup
   * @implNote Interrupts the current thread when {@link InterruptedException} is observed during poll.
   * @since RADAR 0.1-doc
   */
  public void run() throws Exception {
    boolean started = false;
    long persistedCount = 0;
    try {
      packetSource.start();
      started = true;
      log.info("Capture packet source started");

      while (!Thread.currentThread().isInterrupted()) {
        RawFrame frame;
        try {
          Optional<RawFrame> maybeFrame = packetSource.poll();
          if (maybeFrame.isEmpty()) {
            if (packetSource.isExhausted()) {
              log.info("Capture packet source exhausted; stopping capture loop");
              break;
            }
            continue;
          }
          frame = maybeFrame.get();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          log.info("Capture loop interrupted; draining and shutting down");
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
        String previousFlowId = MDC.get("flowId");
        try {
          MDC.put("flowId", formatFlow(segment.flow()));
          persistence.persist(record);
          persistedCount++;
        } finally {
          if (previousFlowId == null) {
            MDC.remove("flowId");
          } else {
            MDC.put("flowId", previousFlowId);
          }
        }
        metrics.increment("capture.segment.persisted");
      }
    } finally {
      try {
        persistence.flush();
        log.info("Capture persistence flushed after {} segments", persistedCount);
      } catch (Exception ex) {
        log.error("Failed to flush capture persistence", ex);
        throw ex;
      } finally {
        try {
          persistence.close();
          log.info("Capture persistence port closed");
        } catch (Exception ex) {
          log.error("Failed to close capture persistence port", ex);
          throw ex;
        } finally {
          if (started) {
            try {
              packetSource.close();
              log.info("Capture packet source closed");
            } catch (Exception ex) {
              log.error("Failed to close packet source", ex);
              throw ex;
            }
          }
        }
      }
    }
  }

  /**
   * Determines whether a segment represents a pure ACK frame.
   *
   * @param segment segment under inspection
   * @return {@code true} if the segment contains only the ACK flag
   */
  private static boolean isPureAck(TcpSegment segment) {
    return segment.payload().length == 0
        && segment.ack()
        && !segment.syn()
        && !segment.fin()
        && !segment.rst()
        && !segment.psh();
  }

  /**
   * Converts a decoded TCP segment into a persisted record.
   *
   * @param timestampMicros capture timestamp in microseconds
   * @param segment decoded TCP segment
   * @return record ready for persistence
   */
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

  private static String formatFlow(FiveTuple flow) {
    return flow.srcIp() + ":" + flow.srcPort() + "->" + flow.dstIp() + ":" + flow.dstPort();
  }
}
