package ca.gc.cra.radar.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the capture CLI, covering NIC selection, IO modes, and file rotation.
 *
 * @param iface network interface name to capture from
 * @param filter optional BPF filter expression
 * @param snaplen libpcap snap length in bytes
 * @param bufferBytes capture buffer size in bytes
 * @param timeoutMillis polling timeout in milliseconds
 * @param promiscuous whether to enable promiscuous mode
 * @param immediate whether to request immediate mode from libpcap
 * @param outputDirectory directory for persisted segment files in file mode
 * @param fileBase filename prefix for rotated segment files
 * @param rollMiB maximum file size before rotation (mebibytes)
 * @param httpOutputDirectory directory for HTTP poster artifacts
 * @param tn3270OutputDirectory directory for TN3270 poster artifacts
 * @param ioMode capture persistence mode ({@link IoMode#FILE} or {@link IoMode#KAFKA})
 * @param kafkaBootstrap Kafka bootstrap servers (required for Kafka mode)
 * @param kafkaTopicSegments Kafka topic for segments in Kafka mode
 * @param persistenceWorkers number of persistence workers for live processing
 * @param persistenceQueueCapacity capacity of the live persistence queue
 * @param persistenceQueueType queue implementation used for live persistence hand-off
 * @since RADAR 0.1-doc
 */
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
    String kafkaTopicSegments,
    int persistenceWorkers,
    int persistenceQueueCapacity,
    PersistenceQueueType persistenceQueueType) {

  /**
   * Validates capture configuration values.
   *
   * @since RADAR 0.1-doc
   */
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
    if (persistenceWorkers <= 0) {
      throw new IllegalArgumentException("persistenceWorkers must be positive");
    }
    if (persistenceQueueCapacity < persistenceWorkers) {
      throw new IllegalArgumentException("persistenceQueueCapacity must be >= persistenceWorkers");
    }
    ioMode = Objects.requireNonNullElse(ioMode, IoMode.FILE);
    persistenceQueueType = Objects.requireNonNullElse(persistenceQueueType, PersistenceQueueType.ARRAY);
    if (ioMode == IoMode.KAFKA) {
      if (kafkaBootstrap == null || kafkaBootstrap.isBlank()) {
        throw new IllegalArgumentException("kafkaBootstrap is required when ioMode=KAFKA");
      }
      if (kafkaTopicSegments == null || kafkaTopicSegments.isBlank()) {
        throw new IllegalArgumentException("kafkaTopicSegments is required when ioMode=KAFKA");
      }
    }
  }

  /**
   * Provides default capture settings that assume local file output.
   *
   * @return default configuration
   * @since RADAR 0.1-doc
   */
  public static CaptureConfig defaults() {
    int defaultWorkers = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    int defaultQueueCapacity = defaultWorkers * 128;
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
        "radar.segments",
        defaultWorkers,
        defaultQueueCapacity,
        PersistenceQueueType.ARRAY);
  }

  /**
   * Parses CLI-style {@code key=value} arguments into a configuration.
   *
   * @param args CLI arguments; may be {@code null}
   * @return parsed configuration
   * @throws IllegalArgumentException when required values are missing or invalid
   * @since RADAR 0.1-doc
   * @deprecated since RADAR 0.1.1; prefer {@link #fromMap(Map)} fed by {@code CliArgsParser.toMap(args)}.
   */
  @Deprecated(since = "0.1.1", forRemoval = true)
  public static CaptureConfig fromArgs(String[] args) {
    Map<String, String> kv = new HashMap<>();
    if (args != null) {
      for (String arg : args) {
        if (arg == null || arg.isBlank()) {
          continue;
        }
        String[] parts = arg.split("=", 2);
        String key = parts[0];
        String value = parts.length > 1 ? parts[1] : "";
        kv.put(key, value);
      }
    }
    return fromMap(kv);
  }

  /**
   * Creates a configuration from a map of settings.
   *
   * @param args configuration map, typically derived from CLI input
   * @return parsed configuration
   * @throws IllegalArgumentException if validation fails
   * @since RADAR 0.1-doc
   */
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

    int persistenceWorkers = parseInt(kv.get("persistWorkers"), defaults.persistenceWorkers());
    int persistenceQueueCapacity = parseInt(kv.get("persistQueueCapacity"), defaults.persistenceQueueCapacity());
    PersistenceQueueType persistenceQueueType =
        parsePersistenceQueueType(kv.get("persistQueueType"), defaults.persistenceQueueType());

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
        kafkaTopicSegments,
        persistenceWorkers,
        persistenceQueueCapacity,
        persistenceQueueType);
  }

  private static IoMode parseIoMode(String value, IoMode fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return IoMode.fromString(value.trim());
  }

  private static PersistenceQueueType parsePersistenceQueueType(String value, PersistenceQueueType fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return PersistenceQueueType.fromString(value.trim());
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
      String val = map.get(key);
      if (val != null && !val.isBlank()) {
        return val;
      }
    }
    return null;
  }

  /** Queue implementations available for live persistence hand-off. */
  public enum PersistenceQueueType {
    ARRAY,
    LINKED;

    static PersistenceQueueType fromString(String value) {
      String normalized = value.toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "array", "arrayblockingqueue", "array-queue" -> ARRAY;
        case "linked", "linkedblockingqueue", "linked-queue" -> LINKED;
        default -> throw new IllegalArgumentException(
            "persistQueueType must be one of ARRAY or LINKED (was " + value + ")");
      };
    }
  }
}
