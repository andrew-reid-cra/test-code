package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase;
import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.config.IoMode;
import java.util.Map;

/**
 * Entry point for packet capture using the RADAR port-based pipeline.
 * <p>Provides CLI argument parsing and bootstraps the {@link SegmentCaptureUseCase}.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class CaptureCli {
  private CaptureCli() {}

  /**
   * Parses capture CLI arguments and runs the capture pipeline.
   *
   * @param args CLI arguments in {@code key=value} form; {@code iface} is required unless an alternate
   *     {@code PacketSource} is selected via configuration
   * @throws Exception if configuration validation or capture processing fails
   * @since RADAR 0.1-doc
   */
  public static void main(String[] args) throws Exception {
    Map<String, String> kv = CliArgsParser.toMap(args);
    if (!kv.containsKey("iface") || kv.get("iface").isBlank()) {
      usage();
      return;
    }

    CaptureConfig captureCfg;
    try {
      captureCfg = CaptureConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      System.err.println("Invalid capture options: " + ex.getMessage());
      usage();
      return;
    }

    if (captureCfg.ioMode() == IoMode.KAFKA && captureCfg.kafkaBootstrap() == null) {
      System.err.println("Invalid capture options: kafkaBootstrap is required for Kafka mode");
      usage();
      return;
    }

    CompositionRoot root =
        new CompositionRoot(ca.gc.cra.radar.config.Config.defaults(), captureCfg);
    SegmentCaptureUseCase useCase = root.segmentCaptureUseCase();
    useCase.run();
  }

  private static void usage() {
    System.err.println(
        "usage: capture iface=<nic> [bufmb=1024 snap=65535 timeout=1 bpf='<expr>' immediate=true promisc=true] "
            + "[out=out/segments|out=kafka:<topic>] [ioMode=FILE|KAFKA] "
            + "[kafkaBootstrap=host:port] [kafkaTopicSegments=radar.segments]");
  }
}
