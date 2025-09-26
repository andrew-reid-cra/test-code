package ca.gc.cra.radar.api;

import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.logging.Logs;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import ca.gc.cra.radar.validation.Paths;
import java.io.IOException;
import java.nio.file.Path;
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
      "usage: live iface=<nic> [out=PATH|out=kafka:TOPIC] [ioMode=FILE|KAFKA] "
          + "[snaplen=64-262144] [bufmb=4-4096] [timeout=0-60000] [persistWorkers=1-32] "
          + "[persistQueueCapacity=N] [persistQueueType=ARRAY|LINKED] [--enable-bpf --bpf='expr'] "
          + "[--dry-run] [--allow-overwrite] [metricsExporter=otlp|none] [otelEndpoint=URL] "
          + "[otelResourceAttributes=K=V,...]";
  private static final String HELP_TEXT = """
      RADAR live processing pipeline

      Usage:
        live iface=<nic> [options]

      Required:
        iface=NAME                Network interface to capture from

      Optional (validated):
        persistWorkers=1-32      Persistence worker thread count (default scales with CPU cores)
        persistQueueCapacity=N    Bounded hand-off queue size (>= persistWorkers; default persistWorkers*128)
        persistQueueType=ARRAY|LINKED  Queue implementation; ARRAY favours cache locality
        out=PATH                  Segment output directory (default ~/.radar/out/capture/segments)
        out=kafka:TOPIC           Stream segments to Kafka topic (sanitized [A-Za-z0-9._-])
        ioMode=FILE|KAFKA         FILE writes under ~/.radar/out; KAFKA requires kafkaBootstrap
        kafkaBootstrap=HOST:PORT  Validated host/port when ioMode=KAFKA
        snaplen=64-262144         Snap length bytes (default 65535; alias snap=...)
        bufmb=4-4096              Capture buffer in MiB (default 256)
        timeout=0-60000           Poll timeout in ms (default 1000)
        --enable-bpf              Required gate for custom BPF expressions
        --bpf="expr"               Printable ASCII <=1024 bytes; ';' and '`' rejected
        --dry-run                 Validate inputs and print plan without processing
        --allow-overwrite         Permit writing into non-empty output directories
        metricsExporter=otlp|none   Configure metrics exporter (default otlp)
        otelEndpoint=URL            OTLP metrics endpoint when exporter=otlp
        otelResourceAttributes=K=V  Comma-separated OTel resource attributes
        --verbose                 Enable DEBUG logging for troubleshooting
        --help                    Show this message
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

    boolean dryRun = input.hasFlag("--dry-run");
    boolean allowOverwrite = input.hasFlag("--allow-overwrite");
    boolean enableBpfFlag = input.hasFlag("--enable-bpf");

    Map<String, String> kv;
    try {
      kv = CliArgsParser.toMap(input.keyValueArgs());
    } catch (IllegalArgumentException ex) {
      log.error("Invalid argument: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    TelemetryConfigurator.configureMetrics(kv);
    if (enableBpfFlag) {
      kv.put("enableBpf", "true");
    }

    CaptureConfig captureConfig;
    try {
      captureConfig = CaptureConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid live capture configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }


    if (captureConfig.pcapFile() != null) {
      log.error("pcapFile is not supported for live processing; use the capture command instead");
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    ValidatedPaths validated;
    try {
      validated = validateOutputs(captureConfig, allowOverwrite, !dryRun);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid output path configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    if (dryRun) {
      printDryRunPlan(captureConfig, validated, allowOverwrite);
      return ExitCode.SUCCESS;
    }

    if (captureConfig.customBpfEnabled()) {
      log.warn("SECURITY: Custom BPF expression enabled ({} bytes)", captureConfig.filter().length());
      log.debug("SECURITY: BPF expression '{}'", Logs.truncate(captureConfig.filter(), 128));
    }

    CompositionRoot root =
        new CompositionRoot(ca.gc.cra.radar.config.Config.defaults(), captureConfig);

    try {
      log.info("Starting live processing on interface {}", captureConfig.iface());
      root.liveProcessingUseCase().run();
      log.info("Live processing completed on interface {}", captureConfig.iface());
      return ExitCode.SUCCESS;
    } catch (IOException ex) {
      log.error("Live processing I/O failure on interface {}", captureConfig.iface(), ex);
      return ExitCode.IO_ERROR;
    } catch (IllegalArgumentException ex) {
      log.error("Live processing configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Live processing interrupted; shutting down interface {}", captureConfig.iface(), ex);
      return ExitCode.INTERRUPTED;
    } catch (RuntimeException ex) {
      log.error("Unexpected runtime failure in live processing", ex);
      return ExitCode.RUNTIME_FAILURE;
    } catch (Exception ex) {
      log.error("Unexpected checked exception in live processing", ex);
      return ExitCode.RUNTIME_FAILURE;
    }
  }

  private static ValidatedPaths validateOutputs(
      CaptureConfig config, boolean allowOverwrite, boolean createIfMissing) {
    Path segments = config.outputDirectory();
    if (config.ioMode() == IoMode.FILE) {
      segments =
          Paths.validateWritableDir(segments, null, createIfMissing, allowOverwrite);
    }
    Path http =
        Paths.validateWritableDir(config.httpOutputDirectory(), null, createIfMissing, allowOverwrite);
    Path tn3270 =
        Paths.validateWritableDir(config.tn3270OutputDirectory(), null, createIfMissing, allowOverwrite);
    return new ValidatedPaths(segments, http, tn3270);
  }

  private static void printDryRunPlan(
      CaptureConfig config, ValidatedPaths paths, boolean allowOverwrite) {
    CliPrinter.printLines(
        "Live dry-run: no packets will be processed.",
        " Interface        : " + config.iface(),
        " Snaplen          : " + config.snaplen(),
        " Buffer (bytes)   : " + config.bufferBytes(),
        " Timeout (ms)     : " + config.timeoutMillis(),
        " I/O mode         : " + config.ioMode(),
        " Segments dir     : " + paths.segments(),
        " HTTP dir         : " + paths.http(),
        " TN3270 dir       : " + paths.tn3270(),
        " Kafka bootstrap  : " + (config.kafkaBootstrap() == null ? "<none>" : config.kafkaBootstrap()),
        " Kafka topic      : " + (config.kafkaTopicSegments() == null ? "<none>" : config.kafkaTopicSegments()),
        " Allow overwrite  : " + allowOverwrite,
        " BPF filter       : " + Logs.truncate(config.filter(), 96),
        " Re-run without --dry-run to start processing.");
  }

  private record ValidatedPaths(Path segments, Path http, Path tn3270) {}
}
