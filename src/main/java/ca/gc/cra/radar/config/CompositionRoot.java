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
import ca.gc.cra.radar.adapter.kafka.HttpKafkaPersistenceAdapter;
import ca.gc.cra.radar.adapter.kafka.KafkaSegmentReader;
import ca.gc.cra.radar.adapter.kafka.KafkaSegmentRecordReaderAdapter;
import ca.gc.cra.radar.adapter.kafka.SegmentKafkaSinkAdapter;
import ca.gc.cra.radar.adapter.kafka.Tn3270KafkaPersistenceAdapter;
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.capture.PcapPacketSource;
import ca.gc.cra.radar.capture.pcap.PcapFilePacketSource;
import ca.gc.cra.radar.infrastructure.detect.DefaultProtocolDetector;
import ca.gc.cra.radar.infrastructure.metrics.NoOpMetricsAdapter;
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
import java.util.Set;
import java.util.function.Supplier;

/**
 * Temporary composition root used while migrating to the new architecture. It currently exposes
 * stubbed use cases and will be populated with real wiring in subsequent steps.
 */
public final class CompositionRoot {
  private final Config config;
  private final CaptureConfig captureConfig;

  /**
   * Creates a composition root with default capture configuration.
   *
   * @param config base configuration for enabled protocols and defaults
   * @since RADAR 0.1-doc
   */
  public CompositionRoot(Config config) {
    this(config, CaptureConfig.defaults());
  }

  /**
   * Creates a composition root with explicit capture configuration overrides.
   *
   * @param config base configuration for enabled protocols and defaults
   * @param captureConfig capture-specific overrides
   * @since RADAR 0.1-doc
   */
  public CompositionRoot(Config config, CaptureConfig captureConfig) {
    this.config = Objects.requireNonNull(config, "config");
    this.captureConfig = Objects.requireNonNull(captureConfig, "captureConfig");
  }

  /**
   * Provides available protocol modules (HTTP and TN3270).
   *
   * @return available protocol modules (HTTP and TN3270)
   * @since RADAR 0.1-doc
   */
  public List<ProtocolModule> protocolModules() {
    return List.of(new HttpProtocolModule(), new Tn3270ProtocolModule());
  }

  /**
   * Builds the protocol detector wired with the enabled modules.
   *
   * @return protocol detector wired with the enabled modules
   * @since RADAR 0.1-doc
   */
  public ProtocolDetector protocolDetector() {
    return new DefaultProtocolDetector(protocolModules(), config.enabledProtocols());
  }

  /**
   * Builds the segment capture use case using the configured ports.
   *
   * @return capture use case
   * @since RADAR 0.1-doc
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
   * @return live processing use case
   * @since RADAR 0.1-doc
   */
  public LiveProcessingUseCase liveProcessingUseCase() {
    MetricsPort metricsPort = metrics();
    ClockPort clockPort = clock();
    LiveProcessingUseCase.PersistenceSettings persistenceSettings =
        new LiveProcessingUseCase.PersistenceSettings(
            captureConfig.persistenceWorkers(),
            captureConfig.persistenceQueueCapacity(),
            mapQueueType(captureConfig.persistenceQueueType()));
    return new LiveProcessingUseCase(
        newPacketSource(),
        new FrameDecoderLibpcap(),
        new ReorderingFlowAssembler(metricsPort, "live.flowAssembler"),
        protocolDetector(),
        reconstructorFactories(clockPort, metricsPort),
        pairingFactories(),
        messagePersistence(),
        metricsPort,
        config.enabledProtocols(),
        persistenceSettings);
  }

  /**
   * Builds the assemble use case for the provided configuration.
   *
   * @param assembleConfig assemble configuration
   * @return assemble use case
   * @since RADAR 0.1-doc
   */
  public AssembleUseCase assembleUseCase(AssembleConfig assembleConfig) {
    Objects.requireNonNull(assembleConfig, "assembleConfig");
    MetricsPort metricsPort = metrics();
    ClockPort clockPort = clock();
    Set<ProtocolId> enabled = enabledProtocolsForAssemble(assembleConfig);
    return new AssembleUseCase(
        assembleConfig,
        new ReorderingFlowAssembler(metricsPort, "assemble.flowAssembler"),
        protocolDetector(),
        reconstructorFactories(clockPort, metricsPort),
        pairingFactories(),
        messagePersistence(assembleConfig),
        metricsPort,
        enabled,
        buildSegmentReaderFactory());
  }

  /**
   * Supplies the metrics implementation used across use cases.
   *
   * @return metrics implementation used across use cases
   * @since RADAR 0.1-doc
   */
  public MetricsPort metrics() {
    return new NoOpMetricsAdapter();
  }

  /**
   * Supplies the clock implementation used for timestamping.
   *
   * @return clock implementation used for timestamping
   * @since RADAR 0.1-doc
   */
  public ClockPort clock() {
    return new SystemClockAdapter();
  }

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

  private PersistencePort messagePersistence() {
    return buildFilePersistence(
        captureConfig.httpOutputDirectory(),
        captureConfig.tn3270OutputDirectory(),
        true,
        true);
  }

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

  private LiveProcessingUseCase.QueueType mapQueueType(CaptureConfig.PersistenceQueueType type) {
    CaptureConfig.PersistenceQueueType source = Objects.requireNonNullElse(type, CaptureConfig.PersistenceQueueType.ARRAY);
    return switch (source) {
      case ARRAY -> LiveProcessingUseCase.QueueType.ARRAY;
      case LINKED -> LiveProcessingUseCase.QueueType.LINKED;
    };
  }
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

  private Map<ProtocolId, Supplier<MessageReconstructor>> reconstructorFactories(
      ClockPort clock, MetricsPort metrics) {
    return Map.of(
        ProtocolId.HTTP, () -> new HttpMessageReconstructor(clock, metrics),
        ProtocolId.TN3270, () -> new Tn3270MessageReconstructor(clock, metrics));
  }

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



