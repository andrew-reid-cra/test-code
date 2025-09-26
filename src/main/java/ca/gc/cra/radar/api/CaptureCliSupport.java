package ca.gc.cra.radar.api;

import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CaptureProtocol;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.logging.Logs;
import ca.gc.cra.radar.validation.Paths;
import java.nio.file.Path;

/**
 * Shared helpers for capture-oriented CLIs to keep output validation and dry-run messaging aligned.
 */
final class CaptureCliSupport {
  private CaptureCliSupport() {
    // Utility class
  }

  static ValidatedPaths validateOutputs(
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

  static void printDryRunPlan(
      CaptureConfig config, ValidatedPaths paths, boolean allowOverwrite) {
    boolean offline = config.pcapFile() != null;
    String source = offline ? config.pcapFile().toString() : config.iface();
    String filterLabel;
    if (config.customBpfEnabled()) {
      filterLabel = "custom";
    } else if (config.protocol() == CaptureProtocol.GENERIC) {
      filterLabel = "safe default";
    } else {
      filterLabel = config.protocol().displayName() + " default";
    }

    CliPrinter.printLines(
        "Capture dry-run: no packets will be captured.",
        " Mode             : " + (offline ? "OFFLINE" : "LIVE"),
        " Interface        : " + (offline ? "<ignored>" : config.iface()),
        " PCAP file        : " + (offline ? source : "<none>"),
        " Protocol         : " + config.protocol().displayName(),
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
        " BPF filter       : " + filterLabel
            + " -> "
            + Logs.truncate(config.filter(), 96),
        " Re-run without --dry-run to start capture.");
  }

  record ValidatedPaths(Path segments, Path http, Path tn3270) {}
}
