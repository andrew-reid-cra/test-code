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
 * <strong>What:</strong> Coordinates packet capture by polling a {@link PacketSource}, decoding frames, and persisting TCP segments.
 * <p><strong>Why:</strong> Provides a reusable capture loop for live or offline sources.</p>
 * <p><strong>Role:</strong> Application-layer use case on the capture side of the hexagonal architecture.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Start and stop the packet source lifecycle.</li>
 *   <li>Decode frames into {@link TcpSegment}s.</li>
 *   <li>Filter pure ACKs and persist remaining segments.</li>
 *   <li>Emit capture metrics and structured logs.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Not thread-safe; run a single instance per capture pipeline.</p>
 * <p><strong>Performance:</strong> Hot-path loop; minimises allocations by reusing decoder outputs.</p>
 * <p><strong>Observability:</strong> Increments metrics such as {@code capture.segment.persisted} and logs flow IDs via MDC.</p>
 *
 * @implNote Uses {@link SegmentPersistencePort#persist(SegmentRecord)} for each accepted segment and increments metrics counters for observability.
 * @see SegmentPersistencePort
 * @since 0.1.0
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
   * @param packetSource source of raw frames; must not be {@code null}
   * @param frameDecoder decoder that maps frames to TCP segments; must not be {@code null}
   * @param persistence sink for persisted segments; must not be {@code null}
   * @param metrics metrics sink for capture counters; must not be {@code null}
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread before capture begins.</p>
   * <p><strong>Performance:</strong> Constructor performs null checks only.</p>
   * <p><strong>Observability:</strong> No metrics emitted during construction.</p>
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
   * @throws Exception if any port fails during capture, persistence, flushing, or cleanup
   *
   * <p><strong>Concurrency:</strong> Intended for single-threaded execution; the loop checks {@link Thread#isInterrupted()}.</p>
   * <p><strong>Performance:</strong> Polls the packet source continuously, skipping empty results and pure ACKs to cut storage.</p>
   * <p><strong>Observability:</strong> Increments metrics ({@code capture.segment.skipped.*}, {@code capture.segment.persisted}) and logs lifecycle events.</p>
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

  private static String formatFlow(FiveTuple flow) {
    return flow.srcIp() + ":" + flow.srcPort() + "->" + flow.dstIp() + ":" + flow.dstPort();
  }
}

