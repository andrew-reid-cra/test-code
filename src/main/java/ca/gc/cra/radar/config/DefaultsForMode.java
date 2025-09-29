// Repo scan summary: base package ca.gc.cra.radar; entrypoint ca.gc.cra.radar.api.Main dispatches capture|capture-pcap4j|live|assemble|poster via CliInput + CliArgsParser; config builders rely on CaptureConfig.fromMap, AssembleConfig.fromMap, PosterConfig.fromMap, and Config.defaults().
// CLI parsing uses key=value arguments normalized by CliArgsParser with flag handling through CliInput, so defaults must align with those contracts.
package ca.gc.cra.radar.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Supplies flattened default configuration maps for each RADAR CLI mode.
 *
 * <p>The defaults remain the single source of truth for optional YAML keys and legacy CLI invocations.</p>
 */
public final class DefaultsForMode {
  private static final Map<String, String> COMMON_DEFAULTS = buildCommonDefaults();

  private DefaultsForMode() {}

  /**
   * Returns a flattened map of defaults for the requested mode merged with common defaults.
   *
   * @param mode target CLI mode (capture, live, assemble, poster)
   * @return unmodifiable map of default key/value pairs as strings
   */
  public static Map<String, String> asFlatMap(String mode) {
    Objects.requireNonNull(mode, "mode");
    String normalized = mode.trim().toLowerCase(Locale.ROOT);
    Map<String, String> defaults = new LinkedHashMap<>(COMMON_DEFAULTS);
    defaults.putAll(switch (normalized) {
      case "capture" -> buildCaptureDefaults();
      case "live" -> buildLiveDefaults();
      case "assemble" -> buildAssembleDefaults();
      case "poster" -> buildPosterDefaults();
      default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
    });
    return Map.copyOf(defaults);
  }

  private static Map<String, String> buildCommonDefaults() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("metricsExporter", "otlp");
    map.put("otelEndpoint", "");
    map.put("otelResourceAttributes", "");
    map.put("verbose", "false");
    return Map.copyOf(map);
  }

  private static Map<String, String> buildCaptureDefaults() {
    CaptureConfig defaults = CaptureConfig.defaults();
    Map<String, String> map = new LinkedHashMap<>();
    map.put("iface", defaults.iface());
    map.put("pcapFile", "");
    map.put("protocol", defaults.protocol().name());
    map.put("protocolDefaultFilter.GENERIC", "tcp");
    map.put("protocolDefaultFilter.TN3270", "tcp and (port 23 or port 992)");
    map.put("snaplen", Integer.toString(defaults.snaplen()));
    map.put("bufmb", Integer.toString(defaults.bufferBytes() / (1024 * 1024)));
    map.put("timeout", Integer.toString(defaults.timeoutMillis()));
    map.put("promisc", Boolean.toString(defaults.promiscuous()));
    map.put("immediate", Boolean.toString(defaults.immediate()));
    map.put("out", defaults.outputDirectory().toString());
    map.put("fileBase", defaults.fileBase());
    map.put("rollMiB", Integer.toString(defaults.rollMiB()));
    map.put("httpOut", defaults.httpOutputDirectory().toString());
    map.put("tnOut", defaults.tn3270OutputDirectory().toString());
    map.put("ioMode", defaults.ioMode().name());
    map.put("kafkaBootstrap", "");
    map.put("kafkaTopicSegments", defaults.kafkaTopicSegments());
    map.put("persistWorkers", Integer.toString(defaults.persistenceWorkers()));
    map.put("persistQueueCapacity", Integer.toString(defaults.persistenceQueueCapacity()));
    map.put("persistQueueType", defaults.persistenceQueueType().name());
    map.put("tn3270.emitScreenRenders", Boolean.toString(defaults.tn3270EmitScreenRenders()));
    map.put(
        "tn3270.screenRenderSampleRate",
        Double.toString(defaults.tn3270ScreenRenderSampleRate()));
    map.put("tn3270.redaction.policy", defaults.tn3270RedactionPolicy());
    map.put("enableBpf", "false");
    map.put("bpf", "");
    map.put("allowOverwrite", "false");
    map.put("dryRun", "false");
    return map;
  }

  private static Map<String, String> buildLiveDefaults() {
    Map<String, String> map = buildCaptureDefaults();
    map.put("httpEnabled", "true");
    map.put("tnEnabled", "false");
    return map;
  }

  private static Map<String, String> buildAssembleDefaults() {
    AssembleConfig defaults = AssembleConfig.defaults();
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ioMode", defaults.ioMode().name());
    map.put("kafkaBootstrap", defaults.kafkaBootstrap().orElse(""));
    map.put("kafkaSegmentsTopic", defaults.kafkaSegmentsTopic());
    map.put("kafkaHttpPairsTopic", defaults.kafkaHttpPairsTopic());
    map.put("kafkaTnPairsTopic", defaults.kafkaTnPairsTopic());
    map.put("in", defaults.inputDirectory().toString());
    map.put("out", defaults.outputDirectory().toString());
    map.put("httpEnabled", Boolean.toString(defaults.httpEnabled()));
    map.put("tnEnabled", Boolean.toString(defaults.tnEnabled()));
    map.put("httpOut", defaults.httpOutputDirectory().map(Path::toString).orElse(""));
    map.put("tnOut", defaults.tnOutputDirectory().map(Path::toString).orElse(""));
    map.put("tn3270.emitScreenRenders", Boolean.toString(defaults.tn3270EmitScreenRenders()));
    map.put(
        "tn3270.screenRenderSampleRate",
        Double.toString(defaults.tn3270ScreenRenderSampleRate()));
    map.put("tn3270.redaction.policy", defaults.tn3270RedactionPolicy());
    map.put("allowOverwrite", "false");
    map.put("dryRun", "false");
    return map;
  }

  private static Map<String, String> buildPosterDefaults() {
    Path base = defaultBaseDirectory();
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ioMode", IoMode.FILE.name());
    map.put("posterOutMode", IoMode.FILE.name());
    map.put("kafkaBootstrap", "");
    map.put("httpIn", base.resolve("assemble").resolve("http").toString());
    map.put("tnIn", base.resolve("assemble").resolve("tn3270").toString());
    map.put("httpOut", base.resolve("poster").resolve("http").toString());
    map.put("tnOut", base.resolve("poster").resolve("tn3270").toString());
    map.put("kafkaHttpReportsTopic", "radar.http.reports");
    map.put("kafkaTnReportsTopic", "radar.tn3270.reports");
    map.put("kafkaHttpPairsTopic", "radar.http.pairs");
    map.put("kafkaTnPairsTopic", "radar.tn3270.pairs");
    map.put("decode", "none");
    map.put("allowOverwrite", "false");
    map.put("dryRun", "false");
    return map;
  }

  private static Path defaultBaseDirectory() {
    String userHome = System.getProperty("user.home", ".");
    return Path.of(userHome, ".radar", "out");
  }
}




