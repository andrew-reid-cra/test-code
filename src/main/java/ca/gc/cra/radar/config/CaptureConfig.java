package ca.gc.cra.radar.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record CaptureConfig(
    String iface,
    String filter,
    int snaplen,
    int bufferBytes,
    int timeoutMillis,
    boolean promiscuous,
    boolean immediate,
    Path outputDirectory,
    String fileBase,
    int rollMiB,
    Path httpOutputDirectory,
    Path tn3270OutputDirectory,
    IoMode ioMode,
    String kafkaBootstrap,
    String kafkaTopicSegments) {

  public CaptureConfig {
    if (iface == null || iface.isBlank()) {
      throw new IllegalArgumentException("iface must be provided");
    }
    if (outputDirectory == null) {
      throw new IllegalArgumentException("outputDirectory must be provided");
    }
    if (httpOutputDirectory == null) {
      throw new IllegalArgumentException("httpOutputDirectory must be provided");
    }
    if (tn3270OutputDirectory == null) {
      throw new IllegalArgumentException("tn3270OutputDirectory must be provided");
    }
    if (fileBase == null || fileBase.isBlank()) {
      throw new IllegalArgumentException("fileBase must be provided");
    }
    if (rollMiB <= 0) {
      throw new IllegalArgumentException("rollMiB must be positive");
    }
    ioMode = Objects.requireNonNullElse(ioMode, IoMode.FILE);
    if (ioMode == IoMode.KAFKA) {
      if (kafkaBootstrap == null || kafkaBootstrap.isBlank()) {
        throw new IllegalArgumentException("kafkaBootstrap is required when ioMode=KAFKA");
      }
      if (kafkaTopicSegments == null || kafkaTopicSegments.isBlank()) {
        throw new IllegalArgumentException("kafkaTopicSegments is required when ioMode=KAFKA");
      }
    }
  }

  public static CaptureConfig defaults() {
    return new CaptureConfig(
        "eth0",
        null,
        65_535,
        1_024 * 1_024 * 1_024,
        1_000,
        true,
        true,
        Path.of("out", "segments"),
        "segments",
        1_024,
        Path.of("out", "http"),
        Path.of("out", "tn3270"),
        IoMode.FILE,
        null,
        "radar.segments");
  }

  public static CaptureConfig fromArgs(String[] args) {
    Map<String, String> kv = new HashMap<>();
    if (args != null) {
      for (String arg : args) {
        if (arg == null || arg.isBlank()) continue;
        String[] parts = arg.split("=", 2);
        String key = parts[0];
        String value = parts.length > 1 ? parts[1] : "";
        kv.put(key, value);
      }
    }
    return fromMap(kv);
  }

  public static CaptureConfig fromMap(Map<String, String> args) {
    Map<String, String> kv = args == null ? Map.of() : new HashMap<>(args);
    CaptureConfig defaults = defaults();

    String iface = normalized(kv.getOrDefault("iface", defaults.iface()));
    if (iface == null) {
      throw new IllegalArgumentException("iface must be provided");
    }

    String filter = normalized(kv.getOrDefault("bpf", defaults.filter()));
    int snap = parseInt(kv.get("snap"), defaults.snaplen());
    int bufferBytes = Math.max(1, parseInt(kv.get("bufmb"), defaults.bufferBytes() / (1024 * 1024))) * 1024 * 1024;
    int timeout = parseInt(kv.get("timeout"), defaults.timeoutMillis());
    boolean promisc = parseBoolean(kv.get("promisc"), defaults.promiscuous());
    boolean immediate = parseBoolean(kv.get("immediate"), defaults.immediate());

    String outRaw = normalized(firstNonBlank(kv, "out", "segmentsOut"));
    Path outputDir = defaults.outputDirectory();
    IoMode ioMode = parseIoMode(kv.get("ioMode"), defaults.ioMode());
    String kafkaTopicSegments = defaults.kafkaTopicSegments();

    if (outRaw != null) {
      if (outRaw.startsWith("kafka:")) {
        ioMode = IoMode.KAFKA;
        kafkaTopicSegments = sanitizeTopic(outRaw.substring("kafka:".length()));
      } else {
        outputDir = Path.of(outRaw);
      }
    }

    String fileBase = normalized(kv.getOrDefault("fileBase", defaults.fileBase()));
    int rollMiB = parseInt(kv.get("rollMiB"), defaults.rollMiB());
    Path httpOut = parsePath(kv.get("httpOut"), defaults.httpOutputDirectory());
    Path tnOut = parsePath(kv.get("tnOut"), defaults.tn3270OutputDirectory());

    String kafkaBootstrap = normalized(kv.get("kafkaBootstrap"));
    kafkaTopicSegments = normalized(kv.getOrDefault("kafkaTopicSegments", kafkaTopicSegments));

    return new CaptureConfig(
        iface,
        filter,
        snap,
        bufferBytes,
        timeout,
        promisc,
        immediate,
        outputDir,
        fileBase,
        rollMiB,
        httpOut,
        tnOut,
        ioMode,
        kafkaBootstrap,
        kafkaTopicSegments);
  }

  private static IoMode parseIoMode(String value, IoMode fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return IoMode.fromString(value.trim());
  }

  private static String sanitizeTopic(String topic) {
    if (topic == null) {
      return null;
    }
    String trimmed = topic.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Kafka topic must not be blank");
    }
    return trimmed;
  }

  private static String normalized(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static int parseInt(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static boolean parseBoolean(String value, boolean fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Boolean.parseBoolean(value.trim());
  }

  private static Path parsePath(String value, Path fallback) {
    String normalized = normalized(value);
    return normalized == null ? fallback : Path.of(normalized);
  }

  private static String firstNonBlank(Map<String, String> map, String... keys) {
    for (String key : keys) {
      String value = map.get(key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
