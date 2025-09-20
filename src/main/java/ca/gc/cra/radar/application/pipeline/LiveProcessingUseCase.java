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

public final class LiveProcessingUseCase {
  private final PacketSource packetSource;
  private final FrameDecoder frameDecoder;
  private final FlowProcessingEngine flowEngine;
  private final PersistencePort persistence;
  private final MetricsPort metrics;
  private final Set<ProtocolId> enabledProtocols;

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
