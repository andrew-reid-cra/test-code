package ca.gc.cra.radar.api;

import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the live RADAR capture pipeline from CLI key-value arguments.
 *
 * @since RADAR 0.1-doc
 */
public final class LiveCli {
  private static final Logger log = LoggerFactory.getLogger(LiveCli.class);
  private static final String SUMMARY_USAGE =
      "usage: live iface=<nic> [bufmb=1024 snap=65535 timeout=1 bpf='<expr>' immediate=true promisc=true]";
  private static final String HELP_TEXT = """
      RADAR live processing pipeline

      Usage:
        live iface=<nic> [options]

      Required options:
        iface=NAME                Network interface to capture from

      Common options:
        bpf='expr'                Optional BPF filter expression
        out=PATH|out=kafka:TOPIC  Optional capture output override
        --help                    Show detailed help
        --verbose                 Enable DEBUG logging for troubleshooting

      Examples:
        live iface=en0 bpf='tcp port 80'
        live iface=eth0 --verbose
      """;

  private LiveCli() {}

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
   * Executes the live CLI logic and returns the resulting exit code.
   *
   * @param args raw CLI arguments
   * @return exit code signalling success or failure
   */
  static ExitCode run(String[] args) {
    CliInput input = CliInput.parse(args);
    if (input.help()) {
      CliPrinter.println(HELP_TEXT.stripTrailing());
      return ExitCode.SUCCESS;
    }
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for live CLI");
    }

    Map<String, String> kv = CliArgsParser.toMap(input.keyValueArgs());
    String iface = kv.get("iface");
    if (iface == null || iface.isBlank()) {
      log.error("Missing required argument iface=<nic>");
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    CaptureConfig captureConfig;
    try {
      captureConfig = CaptureConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid live capture configuration: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    }

    CompositionRoot root =
        new CompositionRoot(ca.gc.cra.radar.config.Config.defaults(), captureConfig);

    try {
      log.info("Starting live processing on interface {}", iface);
      root.liveProcessingUseCase().run();
      log.info("Live processing completed on interface {}", iface);
      return ExitCode.SUCCESS;
    } catch (IOException ex) {
      log.error("Live processing I/O failure on interface {}", iface, ex);
      return ExitCode.IO_ERROR;
    } catch (IllegalArgumentException ex) {
      log.error("Live processing configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Live processing interrupted; shutting down interface {}", iface, ex);
      return ExitCode.INTERRUPTED;
    } catch (RuntimeException ex) {
      log.error("Unexpected runtime failure in live processing", ex);
      return ExitCode.RUNTIME_FAILURE;
    } catch (Exception ex) {
      log.error("Unexpected checked exception in live processing", ex);
      return ExitCode.RUNTIME_FAILURE;
    }
  }
}
