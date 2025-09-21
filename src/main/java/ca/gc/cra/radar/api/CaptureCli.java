package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase;
import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for packet capture using the RADAR port-based pipeline.
 *
 * @since RADAR 0.1-doc
 */
public final class CaptureCli {
  private static final Logger log = LoggerFactory.getLogger(CaptureCli.class);
  private static final String SUMMARY_USAGE =
      "usage: capture iface=<nic> [bufmb=1024 snap=65535 timeout=1 bpf='<expr>' immediate=true promisc=true] "
          + "[out=out/segments|out=kafka:<topic>] [ioMode=FILE|KAFKA] "
          + "[kafkaBootstrap=host:port] [kafkaTopicSegments=radar.segments]";
  private static final String HELP_TEXT = """
      RADAR capture pipeline

      Usage:
        capture iface=<nic> [options]

      Required options:
        iface=NAME                Network interface to capture from unless custom PacketSource configured

      Common options:
        out=PATH|out=kafka:TOPIC  Segment output directory or Kafka topic
        ioMode=FILE|KAFKA         Selects file or Kafka output
        kafkaBootstrap=HOST:PORT  Required when ioMode=KAFKA
        bpf='expr'                Optional BPF filter expression
        --help                    Show detailed help
        --verbose                 Enable DEBUG logging for troubleshooting

      Examples:
        capture iface=en0 out=./cap-out
        capture iface=eth0 out=kafka:radar.segments ioMode=KAFKA kafkaBootstrap=localhost:9092
      """;

  private CaptureCli() {}

  /**
   * Entry point invoked by the JVM.
   *
   * @param args raw CLI arguments
   */
  public static void main(String[] args) {
    ExitCode exit = run(args);
    System.exit(exit.code());
  }

  /**
   * Executes the capture CLI logic and returns a standardized exit code.
   *
   * @param args raw CLI arguments
   * @return exit code that callers can inspect
   */
  static ExitCode run(String[] args) {
    CliInput input = CliInput.parse(args);
    if (input.help()) {
      CliPrinter.println(HELP_TEXT.stripTrailing());
      return ExitCode.SUCCESS;
    }
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for capture CLI");
    }

    Map<String, String> kv = CliArgsParser.toMap(input.keyValueArgs());
    String iface = kv.get("iface");
    if (iface == null || iface.isBlank()) {
      log.error("Missing required argument iface=<nic>");
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    CaptureConfig captureCfg;
    try {
      captureCfg = CaptureConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid capture configuration: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    }

    CompositionRoot root =
        new CompositionRoot(ca.gc.cra.radar.config.Config.defaults(), captureCfg);
    SegmentCaptureUseCase useCase = root.segmentCaptureUseCase();

    try {
      log.info("Starting capture on interface {}", iface);
      useCase.run();
      log.info("Capture completed on interface {}", iface);
      return ExitCode.SUCCESS;
    } catch (IOException ex) {
      log.error("Capture I/O failure on interface {}", iface, ex);
      return ExitCode.IO_ERROR;
    } catch (IllegalArgumentException ex) {
      log.error("Capture configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Capture interrupted; shutting down interface {}", iface, ex);
      return ExitCode.INTERRUPTED;
    } catch (RuntimeException ex) {
      log.error("Unexpected runtime failure in capture pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    } catch (Exception ex) {
      log.error("Unexpected checked exception in capture pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    }
  }
}
