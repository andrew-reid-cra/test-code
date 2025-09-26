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

  private CapturePcap4jCli() {}

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
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for capture-pcap4j CLI");
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

    String ifaceRaw = kv.get("iface");
    String pcapRaw = kv.get("pcapFile");
    if ((ifaceRaw == null || ifaceRaw.isBlank()) && (pcapRaw == null || pcapRaw.isBlank())) {
      log.error("pcap4j capture must supply iface=<nic> or pcapFile=<path>");
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
      Path pcapPath = captureCfg.pcapFile();
      if (!Files.isRegularFile(pcapPath) || !Files.isReadable(pcapPath)) {
        log.error("pcapFile must reference a readable file: {}", pcapPath);
        CliPrinter.println(SUMMARY_USAGE);
        return ExitCode.INVALID_ARGS;
      }
      String ifaceArg = kv.get("iface");
      if (ifaceArg != null && !ifaceArg.isBlank()) {
        log.warn(
            "Ignoring iface={} because pcapFile={} was provided",
            Logs.truncate(ifaceArg, 32),
            captureCfg.pcapFile());
      }
    }

    CaptureCliSupport.ValidatedPaths validated;
    try {
      validated = CaptureCliSupport.validateOutputs(captureCfg, allowOverwrite, !dryRun);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid output path configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    if (dryRun) {
      CaptureCliSupport.printDryRunPlan(captureCfg, validated, allowOverwrite);
      return ExitCode.SUCCESS;
    }

    if (captureCfg.customBpfEnabled()) {
      log.warn(
          "SECURITY: Custom BPF expression enabled ({} bytes)",
          captureCfg.filter().length());
      log.debug(
          "SECURITY: BPF expression '{}'",
          Logs.truncate(captureCfg.filter(), 128));
    } else {
      String filterSource = captureCfg.protocol() == CaptureProtocol.GENERIC
          ? "safe default"
          : captureCfg.protocol().displayName() + " default";
      log.info("Using {} BPF filter '{}'", filterSource, captureCfg.filter());
    }

    PacketSource packetSource = buildPacketSource(captureCfg);
    SegmentPersistencePort persistence = buildPersistence(captureCfg);
    MetricsPort metrics = new OpenTelemetryMetricsAdapter();
    SegmentCaptureUseCase useCase =
        new SegmentCaptureUseCase(packetSource, new FrameDecoderLibpcap(), persistence, metrics);

    try {
      if (offline) {
        log.info("Starting pcap4j offline capture from {}", captureCfg.pcapFile());
      } else {
        log.info("Starting pcap4j capture on interface {}", captureCfg.iface());
      }
      useCase.run();
      if (offline) {
        log.info("pcap4j offline capture completed for {}", captureCfg.pcapFile());
      } else {
        log.info("pcap4j capture completed on interface {}", captureCfg.iface());
      }
      return ExitCode.SUCCESS;
    } catch (IOException ex) {
      if (offline) {
        log.error("pcap4j capture I/O failure reading {}", captureCfg.pcapFile(), ex);
      } else {
        log.error("pcap4j capture I/O failure on interface {}", captureCfg.iface(), ex);
      }
      return ExitCode.IO_ERROR;
    } catch (IllegalArgumentException ex) {
      log.error("pcap4j capture configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      if (offline) {
        log.error("pcap4j capture interrupted while replaying {}", captureCfg.pcapFile(), ex);
      } else {
        log.error("pcap4j capture interrupted; shutting down interface {}", captureCfg.iface(), ex);
      }
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
}
