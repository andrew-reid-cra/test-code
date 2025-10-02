package ca.gc.cra.radar.api;

import ca.gc.cra.radar.adapter.kafka.SegmentKafkaSinkAdapter;
import ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase;
import ca.gc.cra.radar.application.port.MetricsPort;
import ca.gc.cra.radar.application.port.PacketSource;
import ca.gc.cra.radar.application.port.SegmentPersistencePort;
import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CaptureProtocol;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.infrastructure.capture.Pcap4jPacketSource;
import ca.gc.cra.radar.infrastructure.capture.file.pcap.PcapFilePacketSource;
import ca.gc.cra.radar.infrastructure.metrics.OpenTelemetryMetricsAdapter;
import ca.gc.cra.radar.infrastructure.net.FrameDecoderLibpcap;
import ca.gc.cra.radar.infrastructure.persistence.segment.SegmentFileSinkAdapter;
import ca.gc.cra.radar.logging.Logs;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.pcap4j.core.PcapNativeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the pcap4j-backed capture pipeline.
 * <p>
 * Refactoring focus:
 * - Eliminate duplicated offline/online logging branches.
 * - Centralize usage printing + INVALID_ARGS exits.
 * - Keep behavior, arguments, and outputs stable.
 */
public final class CapturePcap4jCli {

  private static final Logger log = LoggerFactory.getLogger(CapturePcap4jCli.class);

  private static final String SUMMARY_USAGE =
      "usage: capture-pcap4j [iface=<nic>|pcapFile=<path>] [out=PATH|out=kafka:TOPIC] [ioMode=FILE|KAFKA] "
          + "[protocol=GENERIC|TN3270] [snaplen=64-262144] [bufmb=4-4096] [timeout=0-60000] [rollMiB=8-10240] "
          + "[--enable-bpf --bpf='expr'] [--dry-run] [--allow-overwrite] "
          + "[metricsExporter=otlp|none] [otelEndpoint=URL] [otelResourceAttributes=K=V,...]";

  private static final String HELP_TEXT = """
      RADAR capture pipeline (pcap4j variant)
      Usage:
        capture-pcap4j [iface=<nic>|pcapFile=<path>] [options]
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
        immediate=true|false      Immediate mode (best effort via pcap4j)
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
        ? Segment outputs match the default `capture` command for apples-to-apples comparisons.
        ? iface is ignored when pcapFile is provided.
        ? Custom BPF expressions emit a SECURITY warning in logs when enabled.
      """;

  private CapturePcap4jCli() {
    // no instances
  }

  /**
   * Entry point invoked by the JVM.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    ExitCode exit = run(args);
    System.exit(exit.code());
  }

  /**
   * Executes the pcap4j capture CLI logic.
   *
   * @param args command-line arguments
   * @return exit code describing the result of execution
   */
  public static ExitCode run(String[] args) {
    CliInput input = CliInput.parse(args);

    if (input.help()) {
      CliPrinter.println(HELP_TEXT.stripTrailing());
      return ExitCode.SUCCESS;
    }

    enableVerboseIfRequested(input);

    CliFlags flags = CliFlags.fromInput(input);

    ParseResult parse = parseArguments(input);
    if (!parse.success()) {
      return parse.exitCode();
    }

    Map<String, String> cliKv = parse.arguments();

    ExitCode sourceCheck = enforceSourceProvided(cliKv);
    if (sourceCheck != null) {
      return sourceCheck;
    }

    applyEnableBpf(cliKv, flags);

    // Configure metrics from CLI K/V (kept as original behavior).
    TelemetryConfigurator.configureMetrics(cliKv);

    final CaptureConfig captureCfg;
    try {
      captureCfg = CaptureConfig.fromMap(cliKv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid capture arguments: {}", ex.getMessage());
      return printUsageAndReturnInvalidArgs();
    }

    OfflineValidation offlineValidation = validateOfflineCapture(captureCfg, cliKv);
    if (!offlineValidation.succeeded()) {
      return offlineValidation.exitCode();
    }

    final boolean offline = offlineValidation.offline();

    final CaptureCliSupport.ValidatedPaths validated;
    try {
      validated = CaptureCliSupport.validateOutputs(
          captureCfg, flags.allowOverwrite(), !flags.dryRun());
    } catch (IllegalArgumentException ex) {
      log.error("Invalid output path configuration: {}", ex.getMessage());
      return printUsageAndReturnInvalidArgs();
    }

    if (flags.dryRun()) {
      CaptureCliSupport.printDryRunPlan(captureCfg, validated, flags.allowOverwrite());
      return ExitCode.SUCCESS;
    }

    logFilterSelection(captureCfg);

    SegmentCaptureUseCase useCase = buildUseCase(captureCfg);

    return executeCapturePipeline(useCase, captureCfg, offline);
  }

  // ----------------------
  // Helpers / Refactoring
  // ----------------------

  private static void enableVerboseIfRequested(CliInput input) {
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for capture-pcap4j CLI");
    }
  }

  private static ParseResult parseArguments(CliInput input) {
    try {
      return ParseResult.success(CliArgsParser.toMap(input.keyValueArgs()));
    } catch (IllegalArgumentException ex) {
      log.error("Invalid argument: {}", ex.getMessage());
      return ParseResult.failure(printUsageAndReturnInvalidArgs());
    }
  }

  private static ExitCode enforceSourceProvided(Map<String, String> cliKv) {
    String ifaceRaw = cliKv.get("iface");
    String pcapRaw = cliKv.get("pcapFile");
    if (isBlank(ifaceRaw) && isBlank(pcapRaw)) {
      log.error("pcap4j capture must supply iface=<nic> or pcapFile=<path>");
      return printUsageAndReturnInvalidArgs();
    }
    return null;
  }

  private static void applyEnableBpf(Map<String, String> cliKv, CliFlags flags) {
    if (flags.enableBpf()) {
      cliKv.put("enableBpf", "true");
    }
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
      return OfflineValidation.failure(printUsageAndReturnInvalidArgs());
    }

    String ifaceArg = cliKv.get("iface");
    if (!isBlank(ifaceArg)) {
      log.warn("Ignoring iface={} because pcapFile={} was provided",
          Logs.truncate(ifaceArg, 32), pcapPath);
    }

    return OfflineValidation.offlineSuccess();
  }

  private static void logFilterSelection(CaptureConfig captureCfg) {
    if (captureCfg.customBpfEnabled()) {
      log.warn("SECURITY: Custom BPF expression enabled ({} bytes)", captureCfg.filter().length());
      log.debug("SECURITY: BPF expression '{}'", Logs.truncate(captureCfg.filter(), 128));
      return;
    }
    String filterSource =
        captureCfg.protocol() == CaptureProtocol.GENERIC ? "safe default" : captureCfg.protocol().displayName() + " default";
    log.info("Using {} BPF filter '{}'", filterSource, captureCfg.filter());
  }

  private static SegmentCaptureUseCase buildUseCase(CaptureConfig captureCfg) {
    PacketSource packetSource = buildPacketSource(captureCfg);
    SegmentPersistencePort persistence = buildPersistence(captureCfg);
    MetricsPort metrics = new OpenTelemetryMetricsAdapter();
    return new SegmentCaptureUseCase(packetSource, new FrameDecoderLibpcap(), persistence, metrics);
  }

  private static ExitCode executeCapturePipeline(
      SegmentCaptureUseCase useCase, CaptureConfig captureCfg, boolean offline) {

    // Compute once; reuse for all messages to avoid branching duplication.
    final String targetLabel = offline ? "pcapFile" : "interface";
    final String targetValue = offline
        ? String.valueOf(captureCfg.pcapFile())
        : String.valueOf(captureCfg.iface());

    try {
      log.info("Starting pcap4j {} {}", targetLabel, targetValue);

      useCase.run();

      log.info("pcap4j capture completed on {} {}", targetLabel, targetValue);
      return ExitCode.SUCCESS;

    } catch (IOException ex) {
      log.error("pcap4j capture I/O failure on {} {}", targetLabel, targetValue, ex);
      return ExitCode.IO_ERROR;

    } catch (IllegalArgumentException ex) {
      log.error("pcap4j capture configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;

    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("pcap4j capture interrupted on {} {}", targetLabel, targetValue, ex);
      return ExitCode.INTERRUPTED;

    } catch (PcapNativeException ex) {
      log.error("pcap4j native failure", ex);
      return ExitCode.IO_ERROR;

    } catch (RuntimeException ex) {
      log.error("Unexpected runtime failure in pcap4j capture pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;

    } catch (Exception ex) {
      log.error("Unexpected checked exception in pcap4j capture pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    }
  }

  private static PacketSource buildPacketSource(CaptureConfig captureCfg) {
    if (captureCfg.pcapFile() != null) {
      return new PcapFilePacketSource(
          captureCfg.pcapFile(), captureCfg.filter(), captureCfg.snaplen());
    }
    return new Pcap4jPacketSource(
        captureCfg.iface(),
        captureCfg.snaplen(),
        captureCfg.promiscuous(),
        captureCfg.timeoutMillis(),
        captureCfg.bufferBytes(),
        captureCfg.immediate(),
        captureCfg.filter());
  }

  private static SegmentPersistencePort buildPersistence(CaptureConfig captureCfg) {
    if (captureCfg.ioMode() == IoMode.KAFKA) {
      return new SegmentKafkaSinkAdapter(
          captureCfg.kafkaBootstrap(),
          captureCfg.kafkaTopicSegments());
    }
    return new SegmentFileSinkAdapter(
        captureCfg.outputDirectory(),
        captureCfg.fileBase(),
        captureCfg.rollMiB());
  }

  // ----------------------
  // Small utilities
  // ----------------------

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static ExitCode printUsageAndReturnInvalidArgs() {
    printUsage();
    return ExitCode.INVALID_ARGS;
  }

  private static void printUsage() {
    CliPrinter.println(SUMMARY_USAGE);
  }

  private record CliFlags(boolean dryRun, boolean allowOverwrite, boolean enableBpf) {
    static CliFlags fromInput(CliInput input) {
      return new CliFlags(
          input.hasFlag("--dry-run"),
          input.hasFlag("--allow-overwrite"),
          input.hasFlag("--enable-bpf"));
    }
  }

  private record ParseResult(Map<String, String> arguments, ExitCode exitCode) {
    static ParseResult success(Map<String, String> arguments) {
      return new ParseResult(arguments, null);
    }
    static ParseResult failure(ExitCode exitCode) {
      return new ParseResult(null, exitCode);
    }
    boolean success() {
      return exitCode == null;
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
}
