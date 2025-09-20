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
import ca.gc.cra.radar.domain.protocol.ProtocolId;
import ca.gc.cra.radar.infrastructure.capture.PcapPacketSource;
import ca.gc.cra.radar.infrastructure.detect.DefaultProtocolDetector;
import ca.gc.cra.radar.infrastructure.metrics.NoOpMetricsAdapter;
import ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap;
import ca.gc.cra.radar.infrastructure.net.ReorderingFlowAssembler;

import ca.gc.cra.radar.infrastructure.persistence.NoOpPersistenceAdapter;
import ca.gc.cra.radar.infrastructure.persistence.http.HttpSegmentSinkPersistenceAdapter;
import ca.gc.cra.radar.infrastructure.persistence.tn3270.Tn3270SegmentSinkPersistenceAdapter;
import ca.gc.cra.radar.infrastructure.persistence.segment.SegmentFileSinkAdapter;
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
import java.util.Set;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Temporary composition root used while migrating to the new architecture. It currently exposes
 * stubbed use cases and will be populated with real wiring in subsequent steps.
 */
public final class CompositionRoot {
  private final Config config;
  private final CaptureConfig captureConfig;

  public CompositionRoot(Config config) {
    this(config, CaptureConfig.defaults());
  }

  public CompositionRoot(Config config, CaptureConfig captureConfig) {
    this.config = Objects.requireNonNull(config);
    this.captureConfig = Objects.requireNonNull(captureConfig);
  }

  public List<ProtocolModule> protocolModules() {
    return List.of(new HttpProtocolModule(), new Tn3270ProtocolModule());
  }

  public ProtocolDetector protocolDetector() {
    return new DefaultProtocolDetector(protocolModules(), config.enabledProtocols());
  }

  public SegmentCaptureUseCase segmentCaptureUseCase() {
    MetricsPort metricsPort = metrics();
    return new SegmentCaptureUseCase(
        newPacketSource(),
        new FrameDecoderLibpcap(),
        segmentPersistence(),
        metricsPort);
  }

  public LiveProcessingUseCase liveProcessingUseCase() {
    MetricsPort metricsPort = metrics();
    ClockPort clockPort = clock();
    return new LiveProcessingUseCase(
        newPacketSource(),
        new FrameDecoderLibpcap(),
        new ReorderingFlowAssembler(metricsPort, "live.flowAssembler"),
        protocolDetector(),
        reconstructorFactories(clockPort, metricsPort),
        pairingFactories(),
        messagePersistence(),
        metricsPort,
        config.enabledProtocols());
  }

  public AssembleUseCase assembleUseCase(AssembleConfig assembleConfig) {
    MetricsPort metricsPort = metrics();
    Set<ProtocolId> enabled = enabledProtocolsForAssemble(Objects.requireNonNull(assembleConfig, "assembleConfig"));
    return new AssembleUseCase(
        assembleConfig,
        new ReorderingFlowAssembler(metricsPort, "assemble.flowAssembler"),
        protocolDetector(),
        reconstructorFactories(clock(), metricsPort),
        pairingFactories(),
        messagePersistence(assembleConfig),
        metricsPort,
        enabled,
        AssembleUseCase.segmentIoReaderFactory());
  }

  public MetricsPort metrics() {
    return new NoOpMetricsAdapter();
  }

  public ClockPort clock() {
    return new SystemClockAdapter();
  }

  private PacketSource newPacketSource() {
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
    return new SegmentFileSinkAdapter(
        captureConfig.outputDirectory(),
        captureConfig.fileBase(),
        captureConfig.rollMiB());
  }


  private PersistencePort messagePersistence() {
    return messagePersistence(captureConfig.outputDirectory().resolve("pairs"));
  }

  private PersistencePort messagePersistence(Path baseDirectory) {
    PersistencePort tail = new NoOpPersistenceAdapter();
    PersistencePort tn = new Tn3270SegmentSinkPersistenceAdapter(baseDirectory.resolve("tn3270"), tail);
    return new HttpSegmentSinkPersistenceAdapter(baseDirectory.resolve("http"), tn);
  }

  private PersistencePort messagePersistence(AssembleConfig assembleConfig) {
    PersistencePort tail = new NoOpPersistenceAdapter();
    if (assembleConfig.tnEnabled()) {
      tail = new Tn3270SegmentSinkPersistenceAdapter(assembleConfig.effectiveTnOut(), tail);
    }
    if (assembleConfig.httpEnabled()) {
      tail = new HttpSegmentSinkPersistenceAdapter(assembleConfig.effectiveHttpOut(), tail);
    }
    return tail;
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

  public Config config() {
    return config;
  }

  public CaptureConfig captureConfig() {
    return captureConfig;
  }
}























