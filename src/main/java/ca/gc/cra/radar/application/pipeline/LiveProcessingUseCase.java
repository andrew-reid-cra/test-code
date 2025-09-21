package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.FrameDecoder;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.net.RawFrame;
import ca.gc.cra.radar.domain.net.TcpSegment;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Executes live packet capture and protocol reconstruction, persisting message pairs in real time.
 * <p>Runs on a single thread and is not thread-safe; call {@link #run()} once per process.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class LiveProcessingUseCase {
  private final PacketSource packetSource;
  private final FrameDecoder frameDecoder;
  private final FlowProcessingEngine flowEngine;
  private final PersistencePort persistence;
  private final MetricsPort metrics;
  private final Set<ProtocolId> enabledProtocols;

  /**
   * Creates the live processing pipeline by wiring capture, flow assembly, and persistence ports.
   *
   * @param packetSource source of raw frames; must support {@link PacketSource#start()} and {@link PacketSource#poll()}
   * @param frameDecoder converts frames to TCP segments when possible
   * @param flowAssembler orders TCP bytes per flow
   * @param protocolDetector detects protocols for new flows
   * @param reconstructorFactories factories for protocol-specific byte-to-message handlers
   * @param pairingFactories factories that convert message events to {@link MessagePair}s
   * @param persistence sink for emitting message pairs immediately
   * @param metrics metrics sink updated for capture, decode, and pairing events
   * @param enabledProtocols subset of protocols to process; others are ignored
   * @since RADAR 0.1-doc
   */
  public LiveProcessingUseCase(
      PacketSource packetSource,
      FrameDecoder frameDecoder,
      FlowAssembler flowAssembler,
      ProtocolDetector protocolDetector,
      Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories,
      Map<ProtocolId, Supplier<PairingEngine>> pairingFactories,
      PersistencePort persistence,
      MetricsPort metrics,
      Set<ProtocolId> enabledProtocols) {
    this.packetSource = Objects.requireNonNull(packetSource, "packetSource");
    this.frameDecoder = Objects.requireNonNull(frameDecoder, "frameDecoder");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.enabledProtocols = Set.copyOf(Objects.requireNonNull(enabledProtocols, "enabledProtocols"));
    this.flowEngine =
        new FlowProcessingEngine(
            "live",
            Objects.requireNonNull(flowAssembler, "flowAssembler"),
            Objects.requireNonNull(protocolDetector, "protocolDetector"),
            Objects.requireNonNull(reconstructorFactories, "reconstructorFactories"),
            Objects.requireNonNull(pairingFactories, "pairingFactories"),
            this.metrics,
            this.enabledProtocols);
  }

  /**
   * Runs the live loop until interrupted, persisting each reconstructed message pair.
   *
   * @throws Exception if any port fails during capture, decode, or persistence
   * @since RADAR 0.1-doc
   */
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
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }

        Optional<TcpSegment> maybeSegment = frameDecoder.decode(frame);
        if (maybeSegment.isEmpty()) {
          metrics.increment("live.segment.skipped.decode");
          continue;
        }

        List<MessagePair> pairs = flowEngine.onSegment(maybeSegment.get());
        for (MessagePair pair : pairs) {
          persistence.persist(pair);
          metrics.increment("live.pairs.persisted");
        }
      }
    } finally {
      try {
        flowEngine.close();
      } finally {
        persistence.close();
        if (started) {
          packetSource.close();
        }
      }
    }
  }
}
