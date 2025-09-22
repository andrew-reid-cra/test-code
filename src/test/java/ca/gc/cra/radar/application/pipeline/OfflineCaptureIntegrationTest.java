package ca.gc.cra.radar.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.config.AssembleConfig;
import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.config.Config;
import ca.gc.cra.radar.infrastructure.metrics.NoOpMetricsAdapter;
import ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap;
import ca.gc.cra.radar.infrastructure.persistence.segment.SegmentFileSinkAdapter;
import ca.gc.cra.radar.capture.pcap.PcapFilePacketSource;
import ca.gc.cra.radar.testutil.PcapFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineCaptureIntegrationTest {
  @TempDir Path tempDir;

  @Test
  void offlineCaptureFeedsAssemblePipeline() throws Exception {
    Path fixture = Path.of("src", "test", "resources", "pcap", "http_get.pcap").toAbsolutePath().normalize();
    Path segmentsDir = tempDir.resolve("segments");
    Path assembleOut = tempDir.resolve("assembled");

    Files.createDirectories(segmentsDir);
    Files.createDirectories(assembleOut);

    PacketSource packetSource = new PcapFilePacketSource(
        fixture, "tcp port 80", 65535, () -> PcapFixtures.offlineStub(fixture));
    SegmentPersistencePort persistence = new SegmentFileSinkAdapter(segmentsDir, "capture", 8);
    MetricsPort metrics = new NoOpMetricsAdapter();
    SegmentCaptureUseCase captureUseCase =
        new SegmentCaptureUseCase(packetSource, new FrameDecoderLibpcap(), persistence, metrics);
    captureUseCase.run();

    try (Stream<Path> files = Files.list(segmentsDir)) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".segbin")),
          "Expected at least one segment file");
    }

    Map<String, String> assembleArgs = Map.ofEntries(
        Map.entry("in", segmentsDir.toString()),
        Map.entry("out", assembleOut.toString()),
        Map.entry("httpEnabled", "true"),
        Map.entry("tnEnabled", "false"));

    AssembleConfig assembleConfig = AssembleConfig.fromMap(assembleArgs);
    CompositionRoot assembleRoot = new CompositionRoot(Config.defaults(), CaptureConfig.defaults());
    AssembleUseCase assembleUseCase = assembleRoot.assembleUseCase(assembleConfig);
    assembleUseCase.run();

    Path httpOut = assembleConfig.effectiveHttpOut();
    try (Stream<Path> files = Files.list(httpOut)) {
      assertTrue(
          files.anyMatch(path -> path.getFileName().toString().startsWith("blob-")),
          "Expected HTTP blob output");
    }
    try (Stream<Path> files = Files.list(httpOut)) {
      assertTrue(
          files.anyMatch(path -> path.getFileName().toString().startsWith("index-")),
          "Expected HTTP index output");
    }
  }
  @Test
  void offlineTn3270CaptureFeedsAssembler() throws Exception {
    Path fixture = Path.of("src", "test", "resources", "pcap", "tn3270_min.pcap").toAbsolutePath().normalize();
    Path segmentsDir = tempDir.resolve("tn-segments");
    Path assembleOut = tempDir.resolve("tn-assembled");

    Files.createDirectories(segmentsDir);
    Files.createDirectories(assembleOut);

    PacketSource packetSource = new PcapFilePacketSource(
        fixture, "tcp and (port 23 or port 992)", 65535, () -> PcapFixtures.offlineStub(fixture));
    SegmentPersistencePort persistence = new SegmentFileSinkAdapter(segmentsDir, "tn-capture", 8);
    MetricsPort metrics = new NoOpMetricsAdapter();
    SegmentCaptureUseCase captureUseCase =
        new SegmentCaptureUseCase(packetSource, new FrameDecoderLibpcap(), persistence, metrics);
    captureUseCase.run();

    try (Stream<Path> files = Files.list(segmentsDir)) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".segbin")),
          "Expected TN3270 segments");
    }

    Map<String, String> assembleArgs = Map.ofEntries(
        Map.entry("in", segmentsDir.toString()),
        Map.entry("out", assembleOut.toString()),
        Map.entry("httpEnabled", "false"),
        Map.entry("tnEnabled", "true"));

    AssembleConfig assembleConfig = AssembleConfig.fromMap(assembleArgs);
    CompositionRoot assembleRoot = new CompositionRoot(Config.defaults(), CaptureConfig.defaults());
    AssembleUseCase assembleUseCase = assembleRoot.assembleUseCase(assembleConfig);
    assembleUseCase.run();

    Path tnOut = assembleConfig.effectiveTnOut();
    try (Stream<Path> files = Files.list(tnOut)) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("blob-tn-")),
          "Expected TN3270 blob output");
    }
    try (Stream<Path> files = Files.list(tnOut)) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("index-tn-")),
          "Expected TN3270 index output");
    }
  }

}


