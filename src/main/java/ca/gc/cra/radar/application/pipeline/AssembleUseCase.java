package ca.gc.cra.radar.application.pipeline;

import ca.gc.cra.radar.application.port.FlowAssembler;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.Tn3270AssemblerPort;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.config.AssembleConfig;
import ca.gc.cra.radar.domain.capture.SegmentRecord;
import ca.gc.cra.radar.domain.flow.FlowDirectionService;
import ca.gc.cra.radar.domain.msg.MessageEvent;
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
 * <strong>What:</strong> Replays captured segment records to reconstruct higher-level protocol message pairs.
 * <p><strong>Why:</strong> Allows operators to assemble offline captures into poster-ready exchanges.</p>
 * <p><strong>Role:</strong> Application-layer use case coordinating domain ports and persistence adapters.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Read {@link SegmentRecord}s from disk.</li>
 *   <li>Feed segments through {@link FlowProcessingEngine} to produce {@link MessagePair}s.</li>
 *   <li>Persist results via {@link PersistencePort} and emit metrics.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Not thread-safe; intended for single-threaded batch execution.</p>
 * <p><strong>Performance:</strong> Streams segments sequentially, relying on flow engine batching and persistence flushing.</p>
 * <p><strong>Observability:</strong> Emits {@code assemble.*} metrics and structured logs (flow IDs, segment counts).</p>
 *
 * @implNote Tracks flow orientation using {@link FlowDirectionService} to determine client/server side for synthesized {@link TcpSegment} instances.
 * @since 0.1.0
 */
public final class AssembleUseCase {
  private static final Logger log = LoggerFactory.getLogger(AssembleUseCase.class);

  private final AssembleConfig config;
  private final FlowProcessingEngine flowEngine;
  private final PersistencePort persistence;
  private final Tn3270AssemblerPort tnAssembler;
  private final MetricsPort metrics;
  private final Set<ProtocolId> enabledProtocols;
  private final SegmentReaderFactory readerFactory;
  private final FlowDirectionService orientations = new FlowDirectionService();

  /**
   * Creates an assemble use case with explicit dependencies.
   *
   * @param config assemble configuration describing input location and limits; must not be {@code null}
   * @param flowAssembler flow assembler implementation used by {@link FlowProcessingEngine}; must not be {@code null}
   * @param protocolDetector detector responsible for classifying flows; must not be {@code null}
   * @param reconstructorFactories factories for message reconstructors keyed by protocol; must not be {@code null}
   * @param pairingFactories factories for pairing engines keyed by protocol; must not be {@code null}
   * @param persistence persistence port receiving assembled pairs; must not be {@code null}
   * @param metrics metrics port used to record assemble statistics; must not be {@code null}
   * @param enabledProtocols whitelist of protocols to process; must not be {@code null}
   * @param readerFactory factory creating {@link SegmentRecordReader}s for the configured input; must not be {@code null}
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread before pipeline execution.</p>
   * <p><strong>Performance:</strong> Initializes {@link FlowProcessingEngine} once; heavy work happens during {@link #run()}.</p>
   * <p><strong>Observability:</strong> Constructor emits no metrics; runtime metrics surface during processing.</p>
   */
  public AssembleUseCase(
      AssembleConfig config,
      FlowAssembler flowAssembler,
      ProtocolDetector protocolDetector,
      Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories,
      Map<ProtocolId, Supplier<PairingEngine>> pairingFactories,
      PersistencePort persistence,
      Tn3270AssemblerPort tnAssembler,
      MetricsPort metrics,
      Set<ProtocolId> enabledProtocols,
      SegmentReaderFactory readerFactory) {
    this.config = Objects.requireNonNull(config, "config");
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.tnAssembler = tnAssembler;
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
   *
   * <p><strong>Concurrency:</strong> Not thread-safe; call once per pipeline execution.</p>
   * <p><strong>Performance:</strong> Processes segments sequentially; persistence flush happens after reading completes.</p>
   * <p><strong>Observability:</strong> Emits MDC flow identifiers, increments {@code assemble.pairs.persisted}, and logs throughput.</p>
   */
  public void run() throws Exception {
    MDC.put("assemble.in", config.inputDirectory().toString());
    long segmentCount = 0;
    long pairCount = 0;
    Exception cleanupFailure = null;
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
            processAssembler(pair);
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
      cleanupFailure = closeResources();
      MDC.remove("assemble.in");
    }
    if (cleanupFailure != null) {
      throw cleanupFailure;
    }
  }

  private Exception closeResources() {
    Exception closeFailure = null;
    try {
      flowEngine.close();
      log.info("Assemble flow engine closed");
    } catch (Exception ex) {
      log.error("Failed to close assemble flow engine", ex);
      closeFailure = ex;
    }
    if (tnAssembler != null) {
      try {
        tnAssembler.close();
        log.info("Assemble TN3270 assembler closed");
      } catch (Exception assemblerCloseFailure) {
        log.error("Failed to close assemble TN3270 assembler", assemblerCloseFailure);
        if (closeFailure == null) {
          closeFailure = assemblerCloseFailure;
        } else {
          closeFailure.addSuppressed(assemblerCloseFailure);
        }
      }
    }
    return closeFailure;
  }

  private void processAssembler(MessagePair pair) throws Exception {
    if (tnAssembler == null || pair == null) {
      return;
    }
    if (!isTnPair(pair)) {
      return;
    }
    tnAssembler.onPair(pair);
  }

  private boolean isTnPair(MessagePair pair) {
    if (pair == null) {
      return false;
    }
    MessageEvent request = pair.request();
    if (request != null && request.protocol() == ProtocolId.TN3270) {
      return true;
    }
    MessageEvent response = pair.response();
    return response != null && response.protocol() == ProtocolId.TN3270;
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
   * <p><strong>Thread-safety:</strong> Implementations are typically single-threaded.</p>
   * <p><strong>Performance:</strong> Should stream records sequentially without loading entire files.</p>
   * <p><strong>Observability:</strong> Callers may measure read latency and errors per implementation.</p>
   */
  public interface SegmentRecordReader extends AutoCloseable {
    /**
     * Returns the next record or {@code null} when depleted.
     *
     * @return next record or {@code null} when complete
     * @throws Exception if reading fails
     *
     * <p><strong>Concurrency:</strong> Not thread-safe unless documented.</p>
     * <p><strong>Performance:</strong> Expected to perform O(1) incremental reads backed by streaming IO.</p>
     * <p><strong>Observability:</strong> Implementations should log or tag read errors.</p>
     */
    SegmentRecord next() throws Exception;

    @Override
    void close() throws Exception;
  }

  /**
   * Factory for {@link SegmentRecordReader} instances tied to an {@link AssembleConfig} input.
   *
   * <p><strong>Thread-safety:</strong> Implementations should be thread-safe; factories are typically stateless.</p>
   * <p><strong>Performance:</strong> Responsible for lightweight reader creation.</p>
   * <p><strong>Observability:</strong> Callers may log factory selection during startup.</p>
   */
  @FunctionalInterface
  public interface SegmentReaderFactory {
    /**
     * Opens a reader bound to the supplied configuration.
     *
     * @param config assemble configuration describing the source; must not be {@code null}
     * @return opened reader
     * @throws Exception if the reader cannot be created
     *
     * <p><strong>Concurrency:</strong> Typically invoked during single-threaded setup.</p>
     * <p><strong>Performance:</strong> Should perform minimal validation before returning a reader.</p>
     * <p><strong>Observability:</strong> Implementations may emit metrics for reader initialization.</p>
     */
    SegmentRecordReader open(AssembleConfig config) throws Exception;
  }

  /**
   * Creates a {@link SegmentReaderFactory} that streams serialized segments via {@link SegmentIoAdapter}.
   *
   * @return factory producing file-based segment readers
   *
   * <p><strong>Concurrency:</strong> Factory is stateless and thread-safe.</p>
   * <p><strong>Performance:</strong> Readers stream from disk using {@link SegmentIoAdapter.Reader}.</p>
   * <p><strong>Observability:</strong> Callers should monitor IO metrics exposed by the adapter.</p>
   */
  public static SegmentReaderFactory segmentIoReaderFactory() {
    return config -> new SegmentIoAdapterRecordReader(new SegmentIoAdapter.Reader(config.inputDirectory()));
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

  private static String formatFlow(FiveTuple flow) {
    return flow.srcIp() + ":" + flow.srcPort() + "->" + flow.dstIp() + ":" + flow.dstPort();
  }
}




