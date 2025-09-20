package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.domain.flow.FlowDirectionService;
import ca.gc.cra.radar.domain.msg.MessageEvent;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.net.ByteStream;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared flow-processing engine that turns TCP segments into paired protocol messages.
 */
final class FlowProcessingEngine implements AutoCloseable {
  private static final int PREFACE_MAX_BYTES = 512;

  private final String metricsPrefix;
  private final FlowAssembler flowAssembler;
  private final ProtocolDetector protocolDetector;
  private final Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories;
  private final Map<ProtocolId, Supplier<PairingEngine>> pairingFactories;
  private final MetricsPort metrics;
  private final Set<ProtocolId> enabledProtocols;

  private final FlowDirectionService directions = new FlowDirectionService();
  private final Map<FlowKey, FlowState> flows = new HashMap<>();

  FlowProcessingEngine(
      String metricsPrefix,
      FlowAssembler flowAssembler,
      ProtocolDetector protocolDetector,
      Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories,
      Map<ProtocolId, Supplier<PairingEngine>> pairingFactories,
      MetricsPort metrics,
      Set<ProtocolId> enabledProtocols) {
    this.metricsPrefix = Objects.requireNonNull(metricsPrefix, "metricsPrefix");
    this.flowAssembler = Objects.requireNonNull(flowAssembler, "flowAssembler");
    this.protocolDetector = Objects.requireNonNull(protocolDetector, "protocolDetector");
    this.reconstructorFactories = Map.copyOf(Objects.requireNonNull(reconstructorFactories, "reconstructorFactories"));
    this.pairingFactories = Map.copyOf(Objects.requireNonNull(pairingFactories, "pairingFactories"));
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.enabledProtocols = Set.copyOf(Objects.requireNonNull(enabledProtocols, "enabledProtocols"));
  }

  List<MessagePair> onSegment(TcpSegment segment) throws Exception {
    metrics.increment(metricsPrefix + ".segment.accepted");

    TcpSegment oriented = orient(segment);
    FlowKey key = FlowKey.from(oriented);
    FlowState state = flows.computeIfAbsent(key, FlowState::new);

    Optional<ByteStream> maybeStream;
    try {
      maybeStream = flowAssembler.accept(oriented);
    } catch (Exception ex) {
      metrics.increment(metricsPrefix + ".flowAssembler.error");
      throw ex;
    }

    List<MessagePair> pairs = List.of();
    if (maybeStream.isPresent()) {
      ByteStream slice = maybeStream.get();
      metrics.observe(metricsPrefix + ".byteStream.bytes", slice.data().length);
      pairs = state.onByteStream(
          slice,
          protocolDetector,
          reconstructorFactories,
          pairingFactories,
          metrics,
          metricsPrefix,
          enabledProtocols);
    }

    if (oriented.fin() || oriented.rst()) {
      closeFlow(key);
    }

    return pairs;
  }

  private TcpSegment orient(TcpSegment segment) {
    FiveTuple flow = segment.flow();
    boolean fromClient =
        directions.fromClient(
            flow.srcIp(), flow.srcPort(), flow.dstIp(), flow.dstPort(), segment.syn(), segment.ack());
    if (segment.fromClient() == fromClient) {
      return segment;
    }
    return new TcpSegment(
        flow,
        segment.sequenceNumber(),
        fromClient,
        segment.payload(),
        segment.fin(),
        segment.syn(),
        segment.rst(),
        segment.psh(),
        segment.ack(),
        segment.timestampMicros());
  }

  private void closeFlow(FlowKey key) {
    FlowState state = flows.remove(key);
    if (state != null) {
      state.close();
    }
    directions.forget(key.client().ip(), key.client().port(), key.server().ip(), key.server().port());
  }

  @Override
  public void close() {
    flows.values().forEach(FlowState::close);
    flows.clear();
  }

  private record FlowKey(Endpoint client, Endpoint server) {
    static FlowKey from(TcpSegment segment) {
      FiveTuple flow = segment.flow();
      Endpoint client = segment.fromClient()
          ? new Endpoint(flow.srcIp(), flow.srcPort())
          : new Endpoint(flow.dstIp(), flow.dstPort());
      Endpoint server = segment.fromClient()
          ? new Endpoint(flow.dstIp(), flow.dstPort())
          : new Endpoint(flow.srcIp(), flow.srcPort());
      return new FlowKey(client, server);
    }
  }

  private record Endpoint(String ip, int port) {}

  private static final class FlowState {
    private final FlowKey key;
    private final ByteArrayOutputStream signature = new ByteArrayOutputStream(PREFACE_MAX_BYTES);

    private MessageReconstructor reconstructor;
    private PairingEngine pairing;
    private boolean unsupported;
    private boolean closed;

    FlowState(FlowKey key) {
      this.key = key;
    }

    List<MessagePair> onByteStream(
        ByteStream slice,
        ProtocolDetector detector,
        Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories,
        Map<ProtocolId, Supplier<PairingEngine>> pairingFactories,
        MetricsPort metrics,
        String metricsPrefix,
        Set<ProtocolId> enabledProtocols)
        throws Exception {
      if (closed || unsupported) {
        return List.of();
      }

      appendSignature(slice.data());

      if (reconstructor == null) {
        ProtocolId detected =
            detector.classify(slice.flow(), key.server().port(), signature.toByteArray());
        if (detected == null || detected == ProtocolId.UNKNOWN) {
          return List.of();
        }
        if (!enabledProtocols.contains(detected)) {
          unsupported = true;
          metrics.increment(metricsPrefix + ".protocol.disabled");
          return List.of();
        }

        Supplier<MessageReconstructor> reconFactory = reconstructorFactories.get(detected);
        Supplier<PairingEngine> pairingFactory = pairingFactories.get(detected);
        if (reconFactory == null || pairingFactory == null) {
          unsupported = true;
          metrics.increment(metricsPrefix + ".protocol.unsupported");
          return List.of();
        }

        reconstructor = Objects.requireNonNull(reconFactory.get(), "reconstructor factory returned null");
        pairing = Objects.requireNonNull(pairingFactory.get(), "pairing factory returned null");
        reconstructor.onStart();
        metrics.increment(metricsPrefix + ".protocol.detected");
      }

      List<MessageEvent> events = reconstructor.onBytes(slice);
      if (events.isEmpty()) {
        return List.of();
      }

      List<MessagePair> pairs = new ArrayList<>(events.size());
      for (MessageEvent event : events) {
        try {
          pairing.accept(event).ifPresent(pairs::add);
        } catch (Exception ex) {
          metrics.increment(metricsPrefix + ".pairing.error");
        }
      }
      if (!pairs.isEmpty()) {
        metrics.increment(metricsPrefix + ".pairs.generated");
      }
      return pairs;
    }

    void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (reconstructor != null) {
        try {
          reconstructor.onClose();
        } catch (RuntimeException ignore) {
          // best-effort close
        }
      }
      if (pairing instanceof AutoCloseable closable) {
        try {
          closable.close();
        } catch (Exception ignore) {
          // best-effort
        }
      }
    }

    private void appendSignature(byte[] data) {
      if (data == null || data.length == 0) {
        return;
      }
      int remaining = PREFACE_MAX_BYTES - signature.size();
      if (remaining <= 0) {
        return;
      }
      signature.write(data, 0, Math.min(remaining, data.length));
    }
  }
}
