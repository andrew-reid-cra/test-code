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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Replays captured segment records to reconstruct higher-level protocol message pairs.
 * <p>Coordinates the {@link FlowProcessingEngine} with persistence and metrics. Not thread-safe;
 * intended for single-threaded batch execution.</p>
 *
 * @implNote Tracks flow orientation using {@link FlowDirectionService} to determine client/server side
 * for synthesized {@link TcpSegment} instances.
 * @since RADAR 0.1-doc
 */
public final class AssembleUseCase {
  private final AssembleConfig config;
  private final FlowProcessingEngine flowEngine;
  private final PersistencePort persistence;
  private final MetricsPort metrics;
  private final Set<ProtocolId> enabledProtocols;
  private final SegmentReaderFactory readerFactory;
  private final FlowDirectionService orientations = new FlowDirectionService();

  /**
   * Creates an assemble use case with the supplied ports and factories.
   *
   * @param config assemble configuration describing inputs and protocol toggles
   * @param flowAssembler assembler that supplies ordered byte streams per TCP flow
   * @param protocolDetector detects protocols for new flows
   * @param reconstructorFactories factories for protocol-specific message reconstructors
   * @param pairingFactories factories for protocol-specific pairing engines
   * @param persistence sink that stores materialized {@link MessagePair} instances
   * @param metrics metrics sink for per-stage counters
   * @param enabledProtocols protocols that should be processed; others are skipped
   * @param readerFactory supplier of {@link SegmentRecordReader} instances bound to {@link AssembleConfig}
   * @since RADAR 0.1-doc
   */
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

  /**
   * Streams segment records through the flow engine and persists resulting message pairs.
   *
   * @throws Exception if reading records, processing flows, or persisting results fails
   * @since RADAR 0.1-doc
   */
  public void run() throws Exception {
    try (PersistencePort sink = this.persistence;
         SegmentRecordReader reader = readerFactory.open(config)) {
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

  /**
   * Reader abstraction over a source of serialized {@link SegmentRecord}s.
   *
   * @since RADAR 0.1-doc
   */
  public interface SegmentRecordReader extends AutoCloseable {
    /**
     * Returns the next segment record or {@code null} when the stream is exhausted.
     *
     * @return next record or {@code null} at end of input
     * @throws Exception if reading fails
     * @since RADAR 0.1-doc
     */
    SegmentRecord next() throws Exception;
  }

  /**
   * Factory for {@link SegmentRecordReader} instances tied to an {@link AssembleConfig} input.
   *
   * @since RADAR 0.1-doc
   */
  @FunctionalInterface
  public interface SegmentReaderFactory {
    /**
     * Opens a reader over the configured segment source.
     *
     * @param config assemble configuration providing the segment input location
     * @return reader that must be closed by the caller
     * @throws Exception if the reader cannot be created
     * @since RADAR 0.1-doc
     */
    SegmentRecordReader open(AssembleConfig config) throws Exception;
  }

  /**
   * Creates a {@link SegmentReaderFactory} that streams serialized segments via {@link SegmentIoAdapter}.
   *
   * @return factory producing file-based segment readers
   * @since RADAR 0.1-doc
   */
  public static SegmentReaderFactory segmentIoReaderFactory() {
    return config -> new SegmentIoAdapterRecordReader(new SegmentIoAdapter.Reader(config.inputDirectory()));
  }

  private static final class SegmentIoAdapterRecordReader implements SegmentRecordReader {
    private final SegmentIoAdapter.Reader delegate;

    private SegmentIoAdapterRecordReader(SegmentIoAdapter.Reader delegate) {
      this.delegate = delegate;
    }

    /**
     * Delegates to the underlying adapter to fetch the next serialized segment.
     *
     * @return next segment or {@code null} when the stream is exhausted
     * @throws Exception if the adapter fails while reading
     * @since RADAR 0.1-doc
     */
    @Override
    public SegmentRecord next() throws Exception {
      return delegate.next();
    }

    /**
     * Closes the underlying adapter stream.
     *
     * @throws Exception if closing the adapter fails
     * @since RADAR 0.1-doc
     */
    @Override
    public void close() throws Exception {
      delegate.close();
    }
  }
}
