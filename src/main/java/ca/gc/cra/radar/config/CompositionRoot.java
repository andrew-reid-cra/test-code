package ca.gc.cra.radar.config;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.application.pipeline.LiveProcessingUseCase;
import ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase;
import ca.gc.cra.radar.application.port.ClockPort;
import ca.gc.cra.radar.application.port.MessageReconstructor;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PairingEngine;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.PersistencePort;
import ca.gc.cra.radar.application.port.ProtocolDetector;
import ca.gc.cra.radar.application.port.ProtocolModule;
import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.application.port.Tn3270AssemblerPort;
import ca.gc.cra.radar.infrastructure.protocol.tn3270.Tn3270AssemblerAdapter;
import ca.gc.cra.radar.infrastructure.protocol.tn3270.Tn3270KafkaPoster;
import ca.gc.cra.radar.infrastructure.protocol.tn3270.TelnetNegotiationFilter;
import ca.gc.cra.radar.adapter.kafka.HttpKafkaPersistenceAdapter;
import ca.gc.cra.radar.adapter.kafka.KafkaSegmentReader;
import ca.gc.cra.radar.adapter.kafka.KafkaSegmentRecordReaderAdapter;
import ca.gc.cra.radar.adapter.kafka.SegmentKafkaSinkAdapter;
import ca.gc.cra.radar.adapter.kafka.Tn3270KafkaPersistenceAdapter;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.capture.live.pcap.PcapPacketSource;
import ca.gc.cra.radar.infrastructure.capture.file.pcap.PcapFilePacketSource;
import ca.gc.cra.radar.infrastructure.detect.DefaultProtocolDetector;
import ca.gc.cra.radar.infrastructure.metrics.OpenTelemetryMetricsAdapter;
import ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap;
import ca.gc.cra.radar.infrastructure.net.ReorderingFlowAssembler;
import ca.gc.cra.radar.infrastructure.persistence.NoOpPersistenceAdapter;
import ca.gc.cra.radar.infrastructure.persistence.http.HttpSegmentSinkPersistenceAdapter;
import ca.gc.cra.radar.infrastructure.persistence.segment.SegmentFileSinkAdapter;
import ca.gc.cra.radar.infrastructure.persistence.tn3270.Tn3270SegmentSinkPersistenceAdapter;
import ca.gc.cra.radar.infrastructure.protocol.http.HttpMessageReconstructor;
import ca.gc.cra.radar.infrastructure.protocol.http.HttpPairingEngineAdapter;
import ca.gc.cra.radar.infrastructure.protocol.http.HttpProtocolModule;
import ca.gc.cra.radar.infrastructure.protocol.tn3270.Tn3270MessageReconstructor;
import ca.gc.cra.radar.infrastructure.protocol.tn3270.Tn3270PairingEngineAdapter;
import ca.gc.cra.radar.infrastructure.protocol.tn3270.Tn3270ProtocolModule;
import ca.gc.cra.radar.infrastructure.time.SystemClockAdapter;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * <strong>What:</strong> Central composition root that wires RADAR use cases to concrete adapters.
 * <p><strong>Why:</strong> Provides a single place to translate configuration into runnable pipelines as the architecture evolves.</p>
 * <p><strong>Role:</strong> Adapter composition root spanning capture -> assemble -> sink stages.</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Instantiate protocol modules and detectors based on enabled protocols.</li>
 *   <li>Construct use case graphs for capture, live processing, and assemble pipelines.</li>
 *   <li>Expose shared adapters such as metrics and clock providers.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Holds immutable configuration references; factory methods create new adapter instances and are not synchronized.</p>
 * <p><strong>Performance:</strong> Factories allocate dependency graphs on demand; invoked during startup, not per-packet.</p>
 * <p><strong>Observability:</strong> Supplies metrics port and propagates configuration-derived metric names.</p>
 *
 * @implNote Wiring remains centralized while RADAR transitions toward modular dependency injection.
 * @since 0.1.0
 * @see ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase
 * @see ca.gc.cra.radar.application.pipeline.LiveProcessingUseCase
 * @see ca.gc.cra.radar.application.pipeline.AssembleUseCase
 */
public final class CompositionRoot {
  private final MetricsPort metrics;
  private static final String DEFAULT_TN3270_USER_TOPIC = "radar.tn3270.user-actions.v1";
  private static final String DEFAULT_TN3270_RENDER_TOPIC = "radar.tn3270.screen-renders.v1";
  private static final String DEFAULT_TN3270_REDACTION_POLICY = "(?i)^(SIN)$";

  private final Config config;
  private final CaptureConfig captureConfig;

  /**
   * Creates a composition root with default capture configuration.
   *
   * @param config base configuration for enabled protocols; must not be {@code null}
   * @throws NullPointerException if {@code config} is {@code null}
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread during startup.</p>
   * <p><strong>Performance:</strong> Stores references; heavy wiring happens when factory methods are invoked.</p>
   * <p><strong>Observability:</strong> Use {@code config} to log which protocols are enabled before executing pipelines.</p>
   */
  public CompositionRoot(Config config) {
    this(config, CaptureConfig.defaults());
  }

  /**
   * Creates a composition root with explicit capture configuration overrides.
   *
   * @param config base configuration for enabled protocols and defaults; must not be {@code null}
   * @param captureConfig capture-specific overrides; must not be {@code null}
   * @throws NullPointerException if any argument is {@code null}
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread during startup.</p>
   * <p><strong>Performance:</strong> Stores references; adapters instantiated lazily by factory methods.</p>
   * <p><strong>Observability:</strong> Allows tests to inject deterministic capture settings for repeatable runs.</p>
   */
  public CompositionRoot(Config config, CaptureConfig captureConfig) {
    this(config, captureConfig, new OpenTelemetryMetricsAdapter());
  }

  /**
   * Creates a composition root with explicit capture configuration and metrics adapter overrides.
   *
   * @param config base configuration for enabled protocols and defaults; must not be {@code null}
   * @param captureConfig capture-specific overrides; must not be {@code null}
   * @param metricsPort metrics adapter used by constructed use cases; must not be {@code null}
   * @throws NullPointerException if any argument is {@code null}
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Construct on a single thread; instance is safe for concurrent reads after construction.</p>
   * <p><strong>Performance:</strong> Stores adapter references; further allocations occur when use cases are built.</p>
   * <p><strong>Observability:</strong> Allows callers to inject test metrics adapters for verification.</p>
   */
  public CompositionRoot(Config config, CaptureConfig captureConfig, MetricsPort metricsPort) {
    this.config = Objects.requireNonNull(config, "config");
    this.captureConfig = Objects.requireNonNull(captureConfig, "captureConfig");
    this.metrics = Objects.requireNonNull(metricsPort, "metricsPort");
  }

  /**
   * Provides the protocol modules wired into this composition root.
   *
   * @return immutable list of protocol modules (HTTP and TN3270)
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Creates fresh module instances per call; thread-safe if each caller uses its own list.</p>
   * <p><strong>Performance:</strong> Allocates two module instances and a small list.</p>
   * <p><strong>Observability:</strong> Callers should log enabled protocol IDs for troubleshooting.</p>
   */
  public List<ProtocolModule> protocolModules() {
    return List.of(new HttpProtocolModule(), new Tn3270ProtocolModule());
  }

  /**
   * Builds the protocol detector wired with the enabled modules.
   *
   * @return protocol detector configured with {@link #protocolModules()} and enabled protocol IDs
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Returns a new detector per call; detector is thread-safe for concurrent reads.</p>
   * <p><strong>Performance:</strong> Instantiation cost is negligible compared with packet processing.</p>
   * <p><strong>Observability:</strong> Detector emits metrics via supplied modules; ensure modules register their instrumentation.</p>
   */
  public ProtocolDetector protocolDetector() {
    return new DefaultProtocolDetector(protocolModules(), config.enabledProtocols());
  }

  /**
   * Builds the segment capture use case using the configured ports.
   *
   * @return capture use case ready to run against the configured packet source
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Create once per CLI invocation; resulting use case is not thread-safe for concurrent execution unless documented.</p>
   * <p><strong>Performance:</strong> Allocates adapter graph (packet source, decoder, persistence); heavy processing happens when executing.</p>
   * <p><strong>Observability:</strong> Wires in {@link MetricsPort} so capture metrics (e.g., packet counters) are emitted automatically.</p>
   */
  public SegmentCaptureUseCase segmentCaptureUseCase() {
    MetricsPort metricsPort = metrics();
    return new SegmentCaptureUseCase(
        newPacketSource(),
        new FrameDecoderLibpcap(),
        segmentPersistence(),
        metricsPort);
  }

  /**
   * Builds the live processing use case using the configured ports.
   *
   * @return live processing use case ready to orchestrate end-to-end capture and persistence
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Instantiate once per CLI run; downstream components manage their own thread-safety.</p>
   * <p><strong>Performance:</strong> Allocates flow assembler queues and persistence settings based on {@link CaptureConfig}; heavy work triggered when executing.</p>
   * <p><strong>Observability:</strong> Attaches {@code metricsPort} to record flow assembler latency, detector outcomes, and persistence counters.</p>
   */
  public LiveProcessingUseCase liveProcessingUseCase() {
    MetricsPort metricsPort = metrics();
    ClockPort clockPort = clock();
    LiveProcessingUseCase.PersistenceSettings persistenceSettings =
        new LiveProcessingUseCase.PersistenceSettings(
            captureConfig.persistenceWorkers(),
            captureConfig.persistenceQueueCapacity(),
            mapQueueType(captureConfig.persistenceQueueType()));
    PersistencePort persistencePort = messagePersistence();
    Tn3270AssemblerPort tnAssembler = createTnAssembler(
        captureConfig.kafkaBootstrap(),
        captureConfig.tn3270EmitScreenRenders(),
        captureConfig.tn3270ScreenRenderSampleRate(),
        captureConfig.tn3270RedactionPolicy());
    return new LiveProcessingUseCase(
        newPacketSource(),
        new FrameDecoderLibpcap(),
        new ReorderingFlowAssembler(metricsPort, "live.flowAssembler"),
        protocolDetector(),
        reconstructorFactories(clockPort, metricsPort),
        pairingFactories(),
        persistencePort,
        tnAssembler,
        metricsPort,
        config.enabledProtocols(),
        persistenceSettings);
  }

  /**
   * Builds the assemble use case for the provided configuration.
   *
   * @param assembleConfig assemble configuration describing IO and protocol toggles; must not be {@code null}
   * @return assemble use case wired with appropriate persistence and segment readers
   * @throws NullPointerException if {@code assembleConfig} is {@code null}
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Instantiate per assemble run; resulting use case coordinates several components and is not meant for concurrent reuse.</p>
   * <p><strong>Performance:</strong> Chooses Kafka versus file readers based on {@code assembleConfig}; actual processing cost incurred when executing.</p>
   * <p><strong>Observability:</strong> Metrics port propagates through to flow assembler, detector, and persistence layers.</p>
   */
  public AssembleUseCase assembleUseCase(AssembleConfig assembleConfig) {
    Objects.requireNonNull(assembleConfig, "assembleConfig");
    MetricsPort metricsPort = metrics();
    ClockPort clockPort = clock();
    Set<ProtocolId> enabled = enabledProtocolsForAssemble(assembleConfig);
    PersistencePort persistencePort = messagePersistence(assembleConfig);
    String bootstrap = assembleConfig.kafkaBootstrap().orElse(null);
    Tn3270AssemblerPort tnAssembler = createTnAssembler(
        bootstrap,
        assembleConfig.tn3270EmitScreenRenders(),
        assembleConfig.tn3270ScreenRenderSampleRate(),
        assembleConfig.tn3270RedactionPolicy());
    return new AssembleUseCase(
        assembleConfig,
        new ReorderingFlowAssembler(metricsPort, "assemble.flowAssembler"),
        protocolDetector(),
        reconstructorFactories(clockPort, metricsPort),
        pairingFactories(),
        persistencePort,
        tnAssembler,
        metricsPort,
        enabled,
        buildSegmentReaderFactory());
  }

  /**
   * Supplies the metrics implementation used across use cases.
   *
   * @return metrics implementation used across use cases
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Returns a shared instance; callers must follow the adapter's thread-safety contract.</p>
   * <p><strong>Performance:</strong> Constant-time accessor.</p>
   * <p><strong>Observability:</strong> Enables consistent metric emission across capture, live, and assemble pipelines.</p>
   */
  public MetricsPort metrics() {
    return metrics;
  }

  /**
   * Supplies the clock implementation used for timestamping.
   *
   * @return clock implementation used for timestamping
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Creates a new {@link SystemClockAdapter} each call; adapter is thread-safe.</p>
   * <p><strong>Performance:</strong> Constant-time allocation.</p>
   * <p><strong>Observability:</strong> Clock drives timestamping for metrics and reconstructor decisions.</p>
   */
  public ClockPort clock() {
    return new SystemClockAdapter();
  }

  /**
   * Creates the packet source configured for capture (file or live pcap).
   *
   * @return packet source matching {@link CaptureConfig} settings
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Instantiated per use case; underlying adapters manage their own thread-safety.</p>
   * <p><strong>Performance:</strong> Chooses between file replay and live capture; heavy work deferred to the adapter.</p>
   * <p><strong>Observability:</strong> Applies capture filter and snaplen from configuration to align metrics with operator expectations.</p>
   */
  private PacketSource newPacketSource() {
    if (captureConfig.pcapFile() != null) {
      return new PcapFilePacketSource(
          captureConfig.pcapFile(), captureConfig.filter(), captureConfig.snaplen());
    }
    return new PcapPacketSource(
        captureConfig.iface(),
        captureConfig.snaplen(),
        captureConfig.promiscuous(),
        captureConfig.timeoutMillis(),
        captureConfig.bufferBytes(),
        captureConfig.immediate(),
        captureConfig.filter());
  }

  /**
   * Builds the segment persistence port used during capture.
   *
   * @return persistence port writing captured segments to Kafka or the file system
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Instantiated per capture run.</p>
   * <p><strong>Performance:</strong> Selects sink implementation based on {@link CaptureConfig#ioMode()}.</p>
   * <p><strong>Observability:</strong> Kafka adapters emit producer metrics; file adapter logs roll decisions.</p>
   */
  private SegmentPersistencePort segmentPersistence() {
    if (captureConfig.ioMode() == IoMode.KAFKA) {
      return new SegmentKafkaSinkAdapter(
          captureConfig.kafkaBootstrap(),
          captureConfig.kafkaTopicSegments());
    }
    return new SegmentFileSinkAdapter(
        captureConfig.outputDirectory(),
        captureConfig.fileBase(),
        captureConfig.rollMiB());
  }

  /**
   * Builds persistence for reconstructed message pairs during live processing.
   *
   * @return persistence pipeline for HTTP and TN3270 outputs
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Instantiated per live use case.</p>
   * <p><strong>Performance:</strong> Composes decorator chain of per-protocol sinks.</p>
   * <p><strong>Observability:</strong> Downstream adapters emit protocol-specific metrics.</p>
   */
  private PersistencePort messagePersistence() {
    return buildFilePersistence(
        captureConfig.httpOutputDirectory(),
        captureConfig.tn3270OutputDirectory(),
        true,
        true);
  }

  private Tn3270AssemblerPort createTnAssembler(
      String bootstrap,
      boolean emitScreenRenders,
      double screenRenderSampleRate,
      String redactionPolicy) {
    if (bootstrap == null) {
      return null;
    }
    String trimmed = bootstrap.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    double rate = Math.max(0d, Math.min(1d, screenRenderSampleRate));
    String effectivePolicy = (redactionPolicy == null || redactionPolicy.isBlank())
        ? DEFAULT_TN3270_REDACTION_POLICY
        : redactionPolicy;
    return new Tn3270AssemblerAdapter(
        new Tn3270KafkaPoster(trimmed, DEFAULT_TN3270_USER_TOPIC, DEFAULT_TN3270_RENDER_TOPIC),
        new TelnetNegotiationFilter(),
        buildRedaction(effectivePolicy),
        emitScreenRenders,
        rate);
  }

  private Function<Map<String, String>, Map<String, String>> buildRedaction(String policy) {
    if (policy == null || policy.isBlank()) {
      return Function.identity();
    }
    Pattern matcher = Pattern.compile(policy);
    return input -> {
      if (input == null || input.isEmpty()) {
        return Map.of();
      }
      Map<String, String> sanitized = new LinkedHashMap<>(input.size());
      for (Map.Entry<String, String> entry : input.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (key != null && matcher.matcher(key).matches()) {
          sanitized.put(key, maskSensitiveValue(value));
        } else {
          sanitized.put(key, value);
        }
      }
      return Map.copyOf(sanitized);
    };
  }

  private static String maskSensitiveValue(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    int keep = Math.max(0, Math.min(3, value.length()));
    int maskUntil = value.length() - keep;
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (i < maskUntil && !Character.isWhitespace(c)) {
        sb.append('*');
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private PersistencePort messagePersistence() {
    return buildFilePersistence(
        captureConfig.httpOutputDirectory(),
        captureConfig.tn3270OutputDirectory(),
        true,
        true);
  }

  /**
   * Builds persistence for assembled message pairs, honoring assemble IO mode.
   *
   * @param assembleConfig assemble configuration driving IO choices
   * @return persistence pipeline targeting Kafka or file sinks
   * @throws IllegalStateException if Kafka mode requires bootstrap servers but none supplied
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Instantiated per assemble run.</p>
   * <p><strong>Performance:</strong> Selects Kafka producer versus file writers; heavy lifting deferred to adapters.</p>
   * <p><strong>Observability:</strong> Ensures persistence adapters inherit metric tags from configuration.</p>
   */
  private PersistencePort messagePersistence(AssembleConfig assembleConfig) {
    if (assembleConfig.ioMode() == IoMode.KAFKA) {
      String bootstrap = assembleConfig.kafkaBootstrap()
          .orElseThrow(() -> new IllegalStateException("kafkaBootstrap required for assemble Kafka mode"));
      return buildKafkaPersistence(
          bootstrap,
          assembleConfig.kafkaHttpPairsTopic(),
          assembleConfig.kafkaTnPairsTopic(),
          assembleConfig.httpEnabled(),
          assembleConfig.tnEnabled());
    }
    return buildFilePersistence(
        assembleConfig.effectiveHttpOut(),
        assembleConfig.effectiveTnOut(),
        assembleConfig.httpEnabled(),
        assembleConfig.tnEnabled());
  }

  /**
   * Creates a factory that supplies segment readers based on assemble IO mode.
   *
   * @return segment reader factory switching between file and Kafka readers
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Returned factory is stateless and thread-safe.</p>
   * <p><strong>Performance:</strong> Lazily constructs Kafka readers only when needed.</p>
   * <p><strong>Observability:</strong> Kafka reader path expects callers to propagate bootstrap and topic names into logs.</p>
   */
  private AssembleUseCase.SegmentReaderFactory buildSegmentReaderFactory() {
    AssembleUseCase.SegmentReaderFactory fileFactory = AssembleUseCase.segmentIoReaderFactory();
    return cfg -> {
      if (cfg.ioMode() == IoMode.KAFKA) {
        String bootstrap = cfg.kafkaBootstrap()
            .orElseThrow(() -> new IllegalStateException("kafkaBootstrap required for assemble Kafka mode"));
        KafkaSegmentReader reader = new KafkaSegmentReader(bootstrap, cfg.kafkaSegmentsTopic());
        return new KafkaSegmentRecordReaderAdapter(reader);
      }
      return fileFactory.open(cfg);
    };
  }

  /**
   * Maps capture persistence queue configuration to the live use case enum.
   *
   * @param type configured queue type (may be {@code null})
   * @return queue type used by {@link LiveProcessingUseCase}
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure helper.</p>
   * <p><strong>Performance:</strong> Constant-time switch.</p>
   * <p><strong>Observability:</strong> Queue selection influences backpressure metrics; log chosen type.</p>
   */
  private LiveProcessingUseCase.QueueType mapQueueType(CaptureConfig.PersistenceQueueType type) {
    CaptureConfig.PersistenceQueueType source = Objects.requireNonNullElse(type, CaptureConfig.PersistenceQueueType.ARRAY);
    return switch (source) {
      case ARRAY -> LiveProcessingUseCase.QueueType.ARRAY;
      case LINKED -> LiveProcessingUseCase.QueueType.LINKED;
    };
  }
  /**
   * Derives enabled protocol IDs for the assemble pipeline.
   *
   * @param assembleConfig assemble configuration
   * @return enabled protocol IDs
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure helper.</p>
   * <p><strong>Performance:</strong> Constant-time checks.</p>
   * <p><strong>Observability:</strong> Results should be logged to confirm which protocol reports will be generated.</p>
   */
  private Set<ProtocolId> enabledProtocolsForAssemble(AssembleConfig assembleConfig) {
    Set<ProtocolId> enabled = new HashSet<>();
    if (assembleConfig.httpEnabled()) {
      enabled.add(ProtocolId.HTTP);
    }
    if (assembleConfig.tnEnabled()) {
      enabled.add(ProtocolId.TN3270);
    }
    return enabled;
  }

  /**
   * Builds a decorator chain for file-based message persistence.
   *
   * @param httpDirectory directory for HTTP outputs
   * @param tnDirectory directory for TN3270 outputs
   * @param httpEnabled whether HTTP persistence should be attached
   * @param tnEnabled whether TN3270 persistence should be attached
   * @return persistence port writing to the file system
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Instantiated per use case.</p>
   * <p><strong>Performance:</strong> Decorator order ensures tail remains lightweight when protocols are disabled.</p>
   * <p><strong>Observability:</strong> File adapters log write destinations and rollovers.</p>
   */
  private PersistencePort buildFilePersistence(
      Path httpDirectory,
      Path tnDirectory,
      boolean httpEnabled,
      boolean tnEnabled) {
    PersistencePort tail = new NoOpPersistenceAdapter();
    if (tnEnabled) {
      tail = new Tn3270SegmentSinkPersistenceAdapter(tnDirectory, tail);
    }
    if (httpEnabled) {
      tail = new HttpSegmentSinkPersistenceAdapter(httpDirectory, tail);
    }
    return tail;
  }

  /**
   * Builds a decorator chain for Kafka-based message persistence.
   *
   * @param bootstrap Kafka bootstrap servers
   * @param httpPairsTopic Kafka topic for HTTP pairs
   * @param tnPairsTopic Kafka topic for TN3270 pairs
   * @param httpEnabled whether HTTP persistence should be attached
   * @param tnEnabled whether TN3270 persistence should be attached
   * @return persistence port writing to Kafka topics
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Instantiated per use case.</p>
   * <p><strong>Performance:</strong> Initializes producers lazily inside adapters.</p>
   * <p><strong>Observability:</strong> Kafka adapters emit producer metrics tagged with supplied topics.</p>
   */
  private PersistencePort buildKafkaPersistence(
      String bootstrap,
      String httpPairsTopic,
      String tnPairsTopic,
      boolean httpEnabled,
      boolean tnEnabled) {
    PersistencePort tail = new NoOpPersistenceAdapter();
    if (tnEnabled) {
      tail = new Tn3270KafkaPersistenceAdapter(bootstrap, tnPairsTopic, tail);
    }
    if (httpEnabled) {
      tail = new HttpKafkaPersistenceAdapter(bootstrap, httpPairsTopic, tail);
    }
    return tail;
  }

  /**
   * Supplies factories for protocol-specific message reconstructors.
   *
   * @param clock clock adapter used for timestamping
   * @param metrics metrics adapter for instrumentation
   * @return map keyed by protocol ID to reconstructor suppliers
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Returned map is immutable; suppliers create new instances per invocation.</p>
   * <p><strong>Performance:</strong> Instantiation deferred until a reconstructor is requested.</p>
   * <p><strong>Observability:</strong> Reconstructors rely on supplied metrics port to publish protocol metrics.</p>
   */
  private Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories(
      ClockPort clock, MetricsPort metrics) {
    return Map.of(
        ProtocolId.HTTP, () -> new HttpMessageReconstructor(clock, metrics),
        ProtocolId.TN3270, () -> new Tn3270MessageReconstructor(clock, metrics));
  }

  /**
   * Supplies factories for protocol-specific pairing engines.
   *
   * @return immutable map of protocol pairing engine suppliers
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Map is immutable; suppliers create new adapters when invoked.</p>
   * <p><strong>Performance:</strong> Constant-time access.</p>
   * <p><strong>Observability:</strong> Pairing engines emit protocol metrics through the shared metrics port.</p>
   */
  private Map<ProtocolId, Supplier<PairingEngine>> pairingFactories() {
    return Map.of(
        ProtocolId.HTTP, HttpPairingEngineAdapter::new,
        ProtocolId.TN3270, Tn3270PairingEngineAdapter::new);
  }

  /**
   * Exposes the base configuration reference.
   *
   * @return base configuration reference
   * @since RADAR 0.1-doc
   */
  public Config config() {
    return config;
  }

  /**
   * Exposes the capture configuration reference.
   *
   * @return capture configuration reference
   * @since RADAR 0.1-doc
   */
  public CaptureConfig captureConfig() {
    return captureConfig;
  }
}







