package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase;
import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CaptureProtocol;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.config.ConfigMerger;
import ca.gc.cra.radar.config.DefaultsForMode;
import ca.gc.cra.radar.config.YamlConfigLoader;
import ca.gc.cra.radar.logging.Logs;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for packet capture using the RADAR port-based pipeline.
 *
 * @since RADAR 0.1-doc
 */
public final class CaptureCli {
  private static final Logger log = LoggerFactory.getLogger(CaptureCli.class);
  private static final String MODE_CAPTURE = "capture";
  private static final String SUMMARY_USAGE =
      "usage: capture [iface=<nic>|pcapFile=<path>] [out=PATH|out=kafka:TOPIC] [ioMode=FILE|KAFKA] "
          + "[protocol=GENERIC|TN3270] [snaplen=64-262144] [bufmb=4-4096] [timeout=0-60000] [rollMiB=8-10240] "
          + "[--enable-bpf --bpf='expr'] [--dry-run] [--allow-overwrite] "
          + "[metricsExporter=otlp|none] [otelEndpoint=URL] [otelResourceAttributes=K=V,...]";
  private static final String HELP_TEXT = """
      RADAR capture pipeline

      Usage:
        capture [iface=<nic>|pcapFile=<path>] [options]

      Required (choose one):
        iface=NAME                Network interface to capture from (e.g., en0)
        pcapFile=PATH             Offline pcap/pcapng trace to replay instead of live capture

      Optional (validated):
        out=PATH                  Capture output directory (default ~/.radar/out/capture/segments)
        out=kafka:TOPIC           Write segments to Kafka topic (A-Z, a-z, 0-9, ., _, -)
        ioMode=FILE|KAFKA         FILE keeps writes under ~/.radar/out; KAFKA requires kafkaBootstrap
        protocol=GENERIC|TN3270   TN3270 applies tcp and (port 23 or port 992) unless bpf= overrides
        kafkaBootstrap=HOST:PORT  Validated host/port (IPv4/IPv6) when ioMode=KAFKA
        snaplen=64-262144         Snap length bytes (default 65535; alias snap=...)
        bufmb=4-4096              Capture buffer size in MiB (default 256)
        timeout=0-60000           Poll timeout in ms (default 1000)
        rollMiB=8-10240           Segment rotation size in MiB (default 512)
        promisc=true|false        Promiscuous mode (default false)
        immediate=true|false      Immediate mode (default false)
        --enable-bpf              Required gate for custom BPF expressions
        --bpf="expr"               Printable ASCII (<=1024 bytes); rejects ';' and ''
        --dry-run                 Validate inputs and print the plan without capturing
        --allow-overwrite         Permit writing into non-empty output directories
        metricsExporter=otlp|none   Configure metrics exporter (default otlp)
        otelEndpoint=URL            OTLP metrics endpoint when exporter=otlp
        otelResourceAttributes=K=V  Comma-separated OTel resource attributes
        --verbose                 Enable DEBUG logging for troubleshooting
        --help                    Show this message

      Notes:
        ? Directories are canonicalized and must be writable; reuse requires --allow-overwrite.
        ? Kafka topics are sanitized to [A-Za-z0-9._-].
        ? iface is ignored when pcapFile is provided.
        ? protocol=TN3270 automatically applies tcp and (port 23 or port 992); override with bpf="...".
        ? Custom BPF expressions emit a SECURITY warning in logs when enabled.
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

    enableVerboseIfRequested(input);
    CliSwitches switches = CliSwitches.fromInput(input);

    Map<String, String> cliKv;
    try {
      cliKv = new LinkedHashMap<>(CliArgsParser.toMap(input.keyValueArgs()));
    } catch (IllegalArgumentException ex) {
      log.error("Invalid argument: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    String configPath = ConfigCliUtils.extractConfigPath(cliKv);
    applyEnableBpfIfRequested(cliKv, switches);

    Optional<Map<String, String>> yamlConfig;
    try {
      yamlConfig = loadYamlConfig(configPath);
    } catch (CliAbort abort) {
      return abort.exitCode();
    }

    Map<String, String> effective;
    try {
      effective = buildEffectiveConfig(yamlConfig, cliKv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid capture configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    ExecutionFlags flags = ExecutionFlags.from(switches, effective);

    Map<String, String> configInputs = new LinkedHashMap<>(effective);
    String metricsExporter = effective.getOrDefault("metricsExporter", "otlp");
    TelemetryConfigurator.configureMetrics(configInputs);

    CaptureConfig captureCfg;
    try {
      captureCfg = CaptureConfig.fromMap(configInputs);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid capture arguments: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    logConfiguredPipeline(captureCfg, metricsExporter);

    OfflineValidation offlineValidation = validateOfflineCapture(captureCfg, cliKv);
    if (!offlineValidation.succeeded()) {
      return offlineValidation.exitCode();
    }
    boolean offline = offlineValidation.offline();

    CaptureCliSupport.ValidatedPaths validated;
    try {
      validated = CaptureCliSupport.validateOutputs(captureCfg, flags.allowOverwrite(), !flags.dryRun());
    } catch (IllegalArgumentException ex) {
      log.error("Invalid output path configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    if (flags.dryRun()) {
      CaptureCliSupport.printDryRunPlan(captureCfg, validated, flags.allowOverwrite());
      return ExitCode.SUCCESS;
    }

    logFilterSelection(captureCfg);
    return executeCapturePipeline(captureCfg, offline);
  }

  private static void enableVerboseIfRequested(CliInput input) {
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for capture CLI");
    }
  }

  private static void applyEnableBpfIfRequested(Map<String, String> cliKv, CliSwitches switches) {
    if (switches.enableBpf()) {
      cliKv.put("enableBpf", "true");
    }
  }

  private static Optional<Map<String, String>> loadYamlConfig(String configPath) throws CliAbort {
    if (configPath == null) {
      return Optional.empty();
    }

    Path yamlPath = Path.of(configPath);
    if (!Files.exists(yamlPath)) {
      log.error("Configuration file does not exist: {}", yamlPath);
      CliPrinter.println(SUMMARY_USAGE);
      throw new CliAbort(ExitCode.INVALID_ARGS);
    }

    try {
      return YamlConfigLoader.load(yamlPath, MODE_CAPTURE);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid YAML configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      throw new CliAbort(ExitCode.INVALID_ARGS);
    } catch (IOException ex) {
      log.error("Unable to read configuration file {}", yamlPath, ex);
      throw new CliAbort(ExitCode.IO_ERROR);
    }
  }

  private static Map<String, String> buildEffectiveConfig(
      Optional<Map<String, String>> yamlConfig, Map<String, String> cliKv) {
    Map<String, String> defaults = DefaultsForMode.asFlatMap(MODE_CAPTURE);
    return ConfigMerger.buildEffectiveConfig(MODE_CAPTURE, yamlConfig, cliKv, defaults, log::warn);
  }

  private static void logConfiguredPipeline(CaptureConfig captureCfg, String metricsExporter) {
    String sink = captureCfg.ioMode() == IoMode.KAFKA
        ? captureCfg.kafkaTopicSegments()
        : captureCfg.outputDirectory().toString();
    log.info(
        "Configured capture pipeline: ioMode={}, sink={}, metricsExporter={}",
        captureCfg.ioMode(),
        sink,
        metricsExporter);
  }

  private static OfflineValidation validateOfflineCapture(
      CaptureConfig captureCfg, Map<String, String> cliKv) {
    boolean offline = captureCfg.pcapFile() != null;
    if (!offline) {
      return OfflineValidation.live();
    }

    Path pcapPath = captureCfg.pcapFile();
    if (!Files.isRegularFile(pcapPath) || !Files.isReadable(pcapPath)) {
      log.error("pcapFile must reference a readable file: {}", pcapPath);
      CliPrinter.println(SUMMARY_USAGE);
      return OfflineValidation.failure(ExitCode.INVALID_ARGS);
    }

    String ifaceArg = cliKv.get("iface");
    if (ifaceArg != null && !ifaceArg.isBlank()) {
      log.warn(
          "Ignoring iface={} because pcapFile={} was provided",
          Logs.truncate(ifaceArg, 32),
          captureCfg.pcapFile());
    }

    return OfflineValidation.offlineSuccess();
  }

  private static void logFilterSelection(CaptureConfig captureCfg) {
    if (captureCfg.customBpfEnabled()) {
      log.warn(
          "SECURITY: Custom BPF expression enabled ({} bytes)",
          captureCfg.filter().length());
      log.debug("SECURITY: BPF expression '{}'", Logs.truncate(captureCfg.filter(), 128));
      return;
    }

    String filterSource = captureCfg.protocol() == CaptureProtocol.GENERIC
        ? "safe default"
        : captureCfg.protocol().displayName() + " default";
    log.info("Using {} BPF filter '{}'", filterSource, captureCfg.filter());
  }

  private static ExitCode executeCapturePipeline(CaptureConfig captureCfg, boolean offline) {
    CompositionRoot root =
        new CompositionRoot(ca.gc.cra.radar.config.Config.defaults(), captureCfg);
    SegmentCaptureUseCase useCase = root.segmentCaptureUseCase();

    try {
      if (offline) {
        log.info("Starting offline capture from {}", captureCfg.pcapFile());
      } else {
        log.info("Starting capture on interface {}", captureCfg.iface());
      }
      useCase.run();
      if (offline) {
        log.info("Offline capture completed for {}", captureCfg.pcapFile());
      } else {
        log.info("Capture completed on interface {}", captureCfg.iface());
      }
      return ExitCode.SUCCESS;
    } catch (IOException ex) {
      if (offline) {
        log.error("Capture I/O failure reading {}", captureCfg.pcapFile(), ex);
      } else {
        log.error("Capture I/O failure on interface {}", captureCfg.iface(), ex);
      }
      return ExitCode.IO_ERROR;
    } catch (IllegalArgumentException ex) {
      log.error("Capture configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      if (offline) {
        log.error("Capture interrupted while replaying {}", captureCfg.pcapFile(), ex);
      } else {
        log.error("Capture interrupted; shutting down interface {}", captureCfg.iface(), ex);
      }
      return ExitCode.INTERRUPTED;
    } catch (RuntimeException ex) {
      log.error("Unexpected runtime failure in capture pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    } catch (Exception ex) {
      log.error("Unexpected checked exception in capture pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    }
  }

  private record CliSwitches(boolean dryRun, boolean allowOverwrite, boolean enableBpf) {
    static CliSwitches fromInput(CliInput input) {
      return new CliSwitches(
          input.hasFlag("--dry-run"),
          input.hasFlag("--allow-overwrite"),
          input.hasFlag("--enable-bpf"));
    }
  }

  private record ExecutionFlags(boolean dryRun, boolean allowOverwrite) {
    static ExecutionFlags from(CliSwitches switches, Map<String, String> configValues) {
      boolean mergedDryRun = switches.dryRun()
          || ConfigCliUtils.parseBoolean(configValues, "dryRun");
      boolean mergedAllowOverwrite = switches.allowOverwrite()
          || ConfigCliUtils.parseBoolean(configValues, "allowOverwrite");
      return new ExecutionFlags(mergedDryRun, mergedAllowOverwrite);
    }
  }

  private record OfflineValidation(boolean offline, ExitCode exitCode) {
    static OfflineValidation live() {
      return new OfflineValidation(false, null);
    }

    static OfflineValidation offlineSuccess() {
      return new OfflineValidation(true, null);
    }

    static OfflineValidation failure(ExitCode exitCode) {
      return new OfflineValidation(false, exitCode);
    }

    boolean succeeded() {
      return exitCode == null;
    }
  }

  private static final class CliAbort extends Exception {
    private final ExitCode exitCode;

    CliAbort(ExitCode exitCode) {
      this.exitCode = exitCode;
    }

    ExitCode exitCode() {
      return exitCode;
    }
  }
}
