package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase;
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
 * Entry point for packet capture using the RADAR port-based pipeline.
 *
 * @since RADAR 0.1-doc
 */
public final class CaptureCli {
  private static final Logger log = LoggerFactory.getLogger(CaptureCli.class);
  private static final String SUMMARY_USAGE =
      "usage: capture [iface=<nic>|pcapFile=<path>] [out=PATH|out=kafka:TOPIC] [ioMode=FILE|KAFKA] "
          + "[snaplen=64-262144] [bufmb=4-4096] [timeout=0-60000] [rollMiB=8-10240] "
          + "[--enable-bpf --bpf='expr'] [--dry-run] [--allow-overwrite]";
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
        kafkaBootstrap=HOST:PORT  Validated host/port (IPv4/IPv6) when ioMode=KAFKA
        snaplen=64-262144         Snap length bytes (default 65535; alias snap=...)
        bufmb=4-4096              Capture buffer size in MiB (default 256)
        timeout=0-60000           Poll timeout in ms (default 1000)
        rollMiB=8-10240           Segment rotation size in MiB (default 512)
        promisc=true|false        Promiscuous mode (default false)
        immediate=true|false      Immediate mode (default false)
        --enable-bpf              Required gate for custom BPF expressions
        --bpf="expr"               Printable ASCII (<=1024 bytes); rejects ';' and '`'
        --dry-run                 Validate inputs and print the plan without capturing
        --allow-overwrite         Permit writing into non-empty output directories
        --verbose                 Enable DEBUG logging for troubleshooting
        --help                    Show this message

      Notes:
        ? Directories are canonicalized and must be writable; reuse requires --allow-overwrite.
        ? Kafka topics are sanitized to [A-Za-z0-9._-].
        ? iface is ignored when pcapFile is provided.
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
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for capture CLI");
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
    if (enableBpfFlag) {
      kv.put("enableBpf", "true");
    }

    CaptureConfig captureCfg;
    try {
      captureCfg = CaptureConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid capture arguments: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }


    boolean offline = captureCfg.pcapFile() != null;
    if (offline) {
      String ifaceArg = kv.get("iface");
      if (ifaceArg != null && !ifaceArg.isBlank()) {
        log.warn("Ignoring iface={} because pcapFile={} was provided",
            Logs.truncate(ifaceArg, 32), captureCfg.pcapFile());
      }
    }

    ValidatedPaths validated;
    try {
      validated = validateOutputs(captureCfg, allowOverwrite, !dryRun);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid output path configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    if (dryRun) {
      printDryRunPlan(captureCfg, validated, allowOverwrite);
      return ExitCode.SUCCESS;
    }

    if (captureCfg.customBpfEnabled()) {
      log.warn(
          "SECURITY: Custom BPF expression enabled ({} bytes)",
          captureCfg.filter().length());
      log.debug("SECURITY: BPF expression '{}'", Logs.truncate(captureCfg.filter(), 128));
    } else {
      log.info("Using default BPF filter '{}'", captureCfg.filter());
    }

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
    boolean offline = config.pcapFile() != null;
    String source = offline ? config.pcapFile().toString() : config.iface();
    CliPrinter.printLines(
        "Capture dry-run: no packets will be captured.",
        " Mode             : " + (offline ? "OFFLINE" : "LIVE"),
        " Interface        : " + (offline ? "<ignored>" : config.iface()),
        " PCAP file        : " + (offline ? source : "<none>"),
        " Snaplen          : " + config.snaplen(),
        " Buffer (bytes)   : " + config.bufferBytes(),
        " Timeout (ms)     : " + config.timeoutMillis(),
        " I/O mode         : " + config.ioMode(),
        " Segments dir     : " + paths.segments(),
        " HTTP dir         : " + paths.http(),
        " TN3270 dir       : " + paths.tn3270(),
        " Kafka bootstrap  : " + (config.kafkaBootstrap() == null ? "<none>" : config.kafkaBootstrap()),
        " Kafka topic      : " + (config.kafkaTopicSegments() == null ? "<none>" : config.kafkaTopicSegments()),
        " Promiscuous mode : " + config.promiscuous(),
        " Immediate mode   : " + config.immediate(),
        " Allow overwrite  : " + allowOverwrite,
        " BPF filter       : "
            + (config.customBpfEnabled() ? "custom" : "safe default")
            + " -> "
            + Logs.truncate(config.filter(), 96),
        " Re-run without --dry-run to start capture.");
  }

  private record ValidatedPaths(Path segments, Path http, Path tn3270) {}
}
