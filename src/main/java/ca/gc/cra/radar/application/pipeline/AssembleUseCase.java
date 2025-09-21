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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
  private static final Logger log = LoggerFactory.getLogger(AssembleUseCase.class);

  private final AssembleConfig config;
  private final FlowProcessingEngine flowEngine;
  private final PersistencePort persistence;
  private final MetricsPort metrics;
  private final Set<ProtocolId> enabledProtocols;
  private final SegmentReaderFactory readerFactory;
  private final FlowDirectionService orientations = new FlowDirectionService();

  /**
   * Creates an assemble use case with explicit dependencies.
   *
   * @param config assemble configuration
   * @param flowAssembler flow assembler implementation
   * @param protocolDetector protocol detector implementation
   * @param reconstructorFactories factories for message reconstructors
   * @param pairingFactories factories for pairing engines
   * @param persistence persistence port
   * @param metrics metrics port
   * @param enabledProtocols protocols to process
   * @param readerFactory factory for segment readers
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
    MDC.put("assemble.in", config.inputDirectory().toString());
    long segmentCount = 0;
    long pairCount = 0;
    try (PersistencePort sink = this.persistence;
         SegmentRecordReader reader = readerFactory.open(config)) {
      log.info("Assemble pipeline reading from {}", config.inputDirectory());
      SegmentRecord record;
      while ((record = reader.next()) != null) {
        segmentCount++;
        TcpSegment segment = toSegment(record);
        String previousFlowId = MDC.get("flowId");
        try {
          MDC.put("flowId", formatFlow(segment.flow()));
          List<MessagePair> pairs = flowEngine.onSegment(segment);
          for (MessagePair pair : pairs) {
            sink.persist(pair);
            metrics.increment("assemble.pairs.persisted");
            pairCount++;
          }
        } finally {
          if (previousFlowId == null) {
            MDC.remove("flowId");
          } else {
            MDC.put("flowId", previousFlowId);
          }
        }
      }
      sink.flush();
      log.info("Assemble pipeline flushed {} message pairs from {} segments", pairCount, segmentCount);
    } catch (Exception ex) {
      log.error("Assemble pipeline failed after processing {} segments", segmentCount, ex);
      throw ex;
    } finally {
      try {
        flowEngine.close();
        log.info("Assemble flow engine closed");
      } catch (Exception ex) {
        log.error("Failed to close assemble flow engine", ex);
        throw ex;
      } finally {
        MDC.remove("assemble.in");
      }
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

  /**
   * Checks whether a flag mask is set.
   *
   * @param flags bitmask to inspect
   * @param mask mask to test
   * @return {@code true} when the mask is present
   */
  private static boolean hasFlag(int flags, int mask) {
    return (flags & mask) != 0;
  }

  /**
   * Reader abstraction over a source of serialized {@link SegmentRecord}s.
   */
  public interface SegmentRecordReader extends AutoCloseable {
    /**
     * Returns the next record or {@code null} when depleted.
     *
     * @return next record or {@code null} when complete
     * @throws Exception if reading fails
     */
    SegmentRecord next() throws Exception;
  }

  /**
   * Factory for {@link SegmentRecordReader} instances tied to an {@link AssembleConfig} input.
   */
  @FunctionalInterface
  public interface SegmentReaderFactory {
    /**
     * Opens a reader bound to the supplied configuration.
     *
     * @param config assemble configuration describing the source
     * @return opened reader
     * @throws Exception if the reader cannot be created
     */
    SegmentRecordReader open(AssembleConfig config) throws Exception;
  }

  /**
   * Creates a {@link SegmentReaderFactory} that streams serialized segments via {@link SegmentIoAdapter}.
   *
   * @return factory producing file-based segment readers
   */
  public static SegmentReaderFactory segmentIoReaderFactory() {
    return config -> new SegmentIoAdapterRecordReader(new SegmentIoAdapter.Reader(config.inputDirectory()));
  }

  /**
   * Concrete reader that adapts {@link SegmentIoAdapter.Reader} to the {@link SegmentRecordReader} interface.
   */
  private static final class SegmentIoAdapterRecordReader implements SegmentRecordReader {
    private final SegmentIoAdapter.Reader delegate;

    /**
     * Creates a reader wrapper around the provided adapter.
     *
     * @param delegate underlying adapter
     */
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

  /**
   * Formats a five-tuple into a compact string for MDC logging.
   *
   * @param flow flow metadata to format
   * @return compact textual representation
   */
  private static String formatFlow(FiveTuple flow) {
    return flow.srcIp() + ":" + flow.srcPort() + "->" + flow.dstIp() + ":" + flow.dstPort();
  }
}
