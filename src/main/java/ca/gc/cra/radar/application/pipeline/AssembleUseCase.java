package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.config.AssembleConfig;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.domain.flow.FlowDirectionService;
import ca.gc.cra.radar.domain.msg.MessagePair;
import ca.gc.cra.radar.domain.net.FiveTuple;
import ca.gc.cra.radar.domain.net.TcpSegment;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.persistence.SegmentIoAdapter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class AssembleUseCase {
  private final AssembleConfig config;
  private final FlowProcessingEngine flowEngine;
  private final PersistencePort persistence;
  private final MetricsPort metrics;
  private final Set<ProtocolId> enabledProtocols;
  private final SegmentReaderFactory readerFactory;
  private final FlowDirectionService orientations = new FlowDirectionService();

  public AssembleUseCase(
      AssembleConfig config,
      FlowAssembler flowAssembler,
      ProtocolDetector protocolDetector,
      Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories,
      Map<ProtocolId, Supplier<PairingEngine>> pairingFactories,
      PersistencePort persistence,
      MetricsPort metrics,
      Set<ProtocolId> enabledProtocols,
      SegmentReaderFactory readerFactory) {
    this.config = Objects.requireNonNull(config, "config");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.enabledProtocols = Set.copyOf(Objects.requireNonNull(enabledProtocols, "enabledProtocols"));
    this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory");
    this.flowEngine =
        new FlowProcessingEngine(
            "assemble",
            Objects.requireNonNull(flowAssembler, "flowAssembler"),
            Objects.requireNonNull(protocolDetector, "protocolDetector"),
            Objects.requireNonNull(reconstructorFactories, "reconstructorFactories"),
            Objects.requireNonNull(pairingFactories, "pairingFactories"),
            this.metrics,
            this.enabledProtocols);
  }

  public void run() throws Exception {
    try (PersistencePort sink = this.persistence;
         SegmentRecordReader reader = readerFactory.open(config.inputDirectory())) {
      SegmentRecord record;
      while ((record = reader.next()) != null) {
        TcpSegment segment = toSegment(record);
        List<MessagePair> pairs = flowEngine.onSegment(segment);
        for (MessagePair pair : pairs) {
          sink.persist(pair);
          metrics.increment("assemble.pairs.persisted");
        }
      }
      sink.flush();
    } finally {
      flowEngine.close();
    }
  }

  private TcpSegment toSegment(SegmentRecord record) {
    FiveTuple flow = new FiveTuple(record.srcIp(), record.srcPort(), record.dstIp(), record.dstPort(), "TCP");
    boolean fin = hasFlag(record.flags(), SegmentRecord.FIN);
    boolean syn = hasFlag(record.flags(), SegmentRecord.SYN);
    boolean rst = hasFlag(record.flags(), SegmentRecord.RST);
    boolean psh = hasFlag(record.flags(), SegmentRecord.PSH);
    boolean ack = hasFlag(record.flags(), SegmentRecord.ACK);

    boolean fromClient =
        orientations.fromClient(record.srcIp(), record.srcPort(), record.dstIp(), record.dstPort(), syn, ack);
    if (fin || rst) {
      orientations.forget(record.srcIp(), record.srcPort(), record.dstIp(), record.dstPort());
    }

    return new TcpSegment(
        flow,
        record.sequence(),
        fromClient,
        record.payload(),
        fin,
        syn,
        rst,
        psh,
        ack,
        record.timestampMicros());
  }

  private static boolean hasFlag(int flags, int mask) {
    return (flags & mask) != 0;
  }

  public interface SegmentRecordReader extends AutoCloseable {
    SegmentRecord next() throws Exception;
  }

  @FunctionalInterface
  public interface SegmentReaderFactory {
    SegmentRecordReader open(Path directory) throws Exception;
  }

  public static SegmentReaderFactory segmentIoReaderFactory() {
    return directory -> new SegmentIoAdapterRecordReader(new SegmentIoAdapter.Reader(directory));
  }

  private static final class SegmentIoAdapterRecordReader implements SegmentRecordReader {
    private final SegmentIoAdapter.Reader delegate;

    private SegmentIoAdapterRecordReader(SegmentIoAdapter.Reader delegate) {
      this.delegate = delegate;
    }

    @Override
    public SegmentRecord next() throws Exception {
      return delegate.next();
    }

    @Override
    public void close() throws Exception {
      delegate.close();
    }
  }
}


