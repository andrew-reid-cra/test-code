package ca.gc.cra.radar.config;

import ca.gc.cra.radar.validation.Net;
import ca.gc.cra.radar.validation.Numbers;
import ca.gc.cra.radar.validation.Strings;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the capture CLI, covering NIC selection, IO modes, and file rotation.
 *
 * @param iface network interface name to capture from
 * @param pcapFile offline capture file when provided
 * @param filter active BPF filter expression (sanitized)
 * @param customBpfEnabled {@code true} when a custom BPF expression was explicitly enabled
 * @param protocol capture protocol hint for default filtering
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
 * @param tn3270EmitScreenRenders whether TN3270 SCREEN_RENDER events should be emitted
 * @param tn3270ScreenRenderSampleRate probability in [0.0, 1.0] for emitting SCREEN_RENDER events
 * @param tn3270RedactionPolicy regex identifying field names to redact before emission
 * @since RADAR 0.1-doc
 */
public record CaptureConfig(
    String iface,
    Path pcapFile,
    String filter,
    boolean customBpfEnabled,
    CaptureProtocol protocol,
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
    PersistenceQueueType persistenceQueueType,
    boolean tn3270EmitScreenRenders,
    double tn3270ScreenRenderSampleRate,
    String tn3270RedactionPolicy) {

  private static final String DEFAULT_SAFE_BPF = CaptureProtocol.GENERIC.defaultFilter();
  private static final int DEFAULT_SNAPLEN = 65_535;
  private static final int DEFAULT_BUFFER_MIB = 256;
  private static final int DEFAULT_TIMEOUT_MILLIS = 1_000;
  private static final int DEFAULT_ROLL_MIB = 512;
  private static final int MIN_SNAPLEN = 64;
  private static final int MAX_SNAPLEN = 262_144;
  private static final int MIN_BUFFER_MIB = 4;
  private static final int MAX_BUFFER_MIB = 4_096;
  private static final int MIN_TIMEOUT_MILLIS = 0;
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MIN_ROLL_MIB = 8;
  private static final int MAX_ROLL_MIB = 10_240;
  private static final int MAX_BPF_LENGTH = 1_024;
  private static final int MIN_PERSIST_WORKERS = 1;
  private static final int MAX_PERSIST_WORKERS = 32;
  private static final int MAX_QUEUE_CAPACITY = 65_536;

  /**
   * Validates capture configuration values.
   *
   * @since RADAR 0.1-doc
   */
  public CaptureConfig {
    boolean offline = pcapFile != null;
    if (offline) {
      pcapFile = normalizePath("pcapFile", pcapFile);
    }
    if (!offline) {
      iface = Strings.requireNonBlank("iface", iface);
    } else if (iface == null || iface.isBlank()) {
      iface = "pcap:" + pcapFile.getFileName();
    } else {
      iface = Strings.requireNonBlank("iface", iface);
    }
    protocol = Objects.requireNonNullElse(protocol, CaptureProtocol.GENERIC);
    filter = Strings.requirePrintableAscii("bpf", filter, MAX_BPF_LENGTH);
    outputDirectory = normalizePath("outputDirectory", outputDirectory);
    httpOutputDirectory = normalizePath("httpOutputDirectory", httpOutputDirectory);
    tn3270OutputDirectory = normalizePath("tn3270OutputDirectory", tn3270OutputDirectory);
    fileBase = Strings.requireNonBlank("fileBase", fileBase);
    Numbers.requireRange("snaplen", snaplen, MIN_SNAPLEN, MAX_SNAPLEN);
    Numbers.requireRange(
        "bufferBytes",
        bufferBytes,
        MIN_BUFFER_MIB * 1_024L * 1_024L,
        MAX_BUFFER_MIB * 1_024L * 1_024L);
    Numbers.requireRange("timeoutMillis", timeoutMillis, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
    Numbers.requireRange("rollMiB", rollMiB, MIN_ROLL_MIB, MAX_ROLL_MIB);
    Numbers.requireRange("persistenceWorkers", persistenceWorkers, MIN_PERSIST_WORKERS, MAX_PERSIST_WORKERS);
    Numbers.requireRange(
        "persistenceQueueCapacity",
        persistenceQueueCapacity,
        persistenceWorkers,
        MAX_QUEUE_CAPACITY);
    ioMode = Objects.requireNonNullElse(ioMode, IoMode.FILE);
    persistenceQueueType = Objects.requireNonNullElse(persistenceQueueType, PersistenceQueueType.ARRAY);
    tn3270RedactionPolicy = tn3270RedactionPolicy == null ? "" : tn3270RedactionPolicy.trim();
    if (Double.isNaN(tn3270ScreenRenderSampleRate)) {
      tn3270ScreenRenderSampleRate = 0d;
    }
    if (tn3270ScreenRenderSampleRate < 0d || tn3270ScreenRenderSampleRate > 1d) {
      throw new IllegalArgumentException("tn3270ScreenRenderSampleRate must be within [0.0,1.0] (was " + tn3270ScreenRenderSampleRate + ')');
    }
    if (kafkaBootstrap != null && !kafkaBootstrap.isBlank()) {
      kafkaBootstrap = Net.validateHostPort(kafkaBootstrap);
    } else {
      kafkaBootstrap = null;
    }
    if (kafkaTopicSegments != null) {
      kafkaTopicSegments = Strings.sanitizeTopic("kafkaTopicSegments", kafkaTopicSegments);
    }
    if (ioMode == IoMode.KAFKA) {
      if (kafkaBootstrap == null) {
        throw new IllegalArgumentException("kafkaBootstrap is required when ioMode=KAFKA");
      }
      if (kafkaTopicSegments == null || kafkaTopicSegments.isBlank()) {
        throw new IllegalArgumentException("kafkaTopicSegments is required when ioMode=KAFKA");
      }
    }
  }

  /**
   * Provides default capture settings that assume local file output with conservative tuning.
   *
   * @return default configuration
   * @since RADAR 0.1-doc
   */
  public static CaptureConfig defaults() {
    Path base = defaultBaseDirectory();
    int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
    int defaultWorkers = Math.min(4, Math.max(1, processors / 2));
    int defaultQueueCapacity = defaultWorkers * 64;
    return new CaptureConfig(
        "eth0",
        null,
        DEFAULT_SAFE_BPF,
        false,
        CaptureProtocol.GENERIC,
        DEFAULT_SNAPLEN,
        DEFAULT_BUFFER_MIB * 1_024 * 1_024,
        DEFAULT_TIMEOUT_MILLIS,
        false,
        false,
        base.resolve("capture").resolve("segments"),
        "segments",
        DEFAULT_ROLL_MIB,
        base.resolve("capture").resolve("http"),
        base.resolve("capture").resolve("tn3270"),
        IoMode.FILE,
        null,
        "radar.segments",
        defaultWorkers,
        defaultQueueCapacity,
        PersistenceQueueType.ARRAY,
        false,
        0d,
        "");
  }

  /**
   * Parses CLI-style {@code key=value} arguments into a configuration.
   *
   * @param args CLI arguments; may be {@code null}
   * @return parsed configuration
   * @throws IllegalArgumentException when required values are missing or invalid
   * @since RADAR 0.1-doc
   */
  public static CaptureConfig fromMap(Map<String, String> args) {
    Map<String, String> kv = args == null ? Map.of() : new HashMap<>(args);
    CaptureConfig defaults = defaults();

    Path pcapFile = parseOptionalPath("pcapFile", kv.get("pcapFile")).orElse(null);

    String ifaceRaw = kv.get("iface");
    String iface;
    if (pcapFile == null) {
      String fallbackIface = (ifaceRaw == null || ifaceRaw.isBlank()) ? defaults.iface() : ifaceRaw;
      iface = Strings.requireNonBlank("iface", fallbackIface);
    } else if (ifaceRaw != null && !ifaceRaw.isBlank()) {
      iface = Strings.requireNonBlank("iface", ifaceRaw);
    } else {
      iface = null;
    }

    boolean bpfAcknowledged = parseBoolean(kv.get("enableBpf"), false);
    CaptureProtocol protocol = CaptureProtocol.fromString(kv.get("protocol"));
    String filter = protocol.defaultFilter();
    boolean customBpf = false;
    String rawFilter = kv.get("bpf");
    if (rawFilter != null && !rawFilter.isBlank()) {
      if (!bpfAcknowledged) {
        throw new IllegalArgumentException("bpf requires --enable-bpf acknowledgement");
      }
      filter = Strings.requirePrintableAscii("bpf", rawFilter, MAX_BPF_LENGTH);
      denyDangerousBpf(filter);
      customBpf = true;
    }

    int snap;
    if (kv.get("snaplen") != null && !kv.get("snaplen").isBlank()) {
      snap = parseBoundedInt(kv, "snaplen", defaults.snaplen(), MIN_SNAPLEN, MAX_SNAPLEN);
    } else {
      snap = parseBoundedInt(kv, "snap", defaults.snaplen(), MIN_SNAPLEN, MAX_SNAPLEN);
    }
    int bufMib = parseBoundedInt(kv, "bufmb", defaults.bufferBytes() / (1_024 * 1_024), MIN_BUFFER_MIB, MAX_BUFFER_MIB);
    int bufferBytes = Math.toIntExact(bufMib * 1_024L * 1_024L);
    int timeout = parseBoundedInt(kv, "timeout", defaults.timeoutMillis(), MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
    boolean promisc = parseBoolean(kv.get("promisc"), defaults.promiscuous());
    boolean immediate = parseBoolean(kv.get("immediate"), defaults.immediate());

    String outRaw = firstNonBlank(kv, "out", "segmentsOut");
    Path outputDir = defaults.outputDirectory();
    IoMode ioMode = parseIoMode(kv.get("ioMode"), defaults.ioMode());
    String kafkaTopicSegments = defaults.kafkaTopicSegments();
    if (outRaw != null) {
      if (outRaw.startsWith("kafka:")) {
        ioMode = IoMode.KAFKA;
        kafkaTopicSegments = Strings.sanitizeTopic("kafkaTopicSegments", outRaw.substring("kafka:".length()));
      } else {
        outputDir = parsePath("out", outRaw);
      }
    }

    String fileBase = Strings.requireNonBlank("fileBase", kv.getOrDefault("fileBase", defaults.fileBase()));
    int rollMiB = parseBoundedInt(kv, "rollMiB", defaults.rollMiB(), MIN_ROLL_MIB, MAX_ROLL_MIB);

    Path httpOut = parseOptionalPath("httpOut", firstNonBlank(kv, "httpOut", "--httpOut"))
        .orElse(defaults.httpOutputDirectory());
    Path tnOut = parseOptionalPath("tnOut", firstNonBlank(kv, "tnOut", "--tnOut"))
        .orElse(defaults.tn3270OutputDirectory());

    boolean tnEmitRenders = parseBoolean(kv.get("tn3270.emitScreenRenders"), defaults.tn3270EmitScreenRenders());
    double tnSampleRate = defaults.tn3270ScreenRenderSampleRate();
    String tnSampleRateRaw = kv.get("tn3270.screenRenderSampleRate");
    if (tnSampleRateRaw != null && !tnSampleRateRaw.isBlank()) {
      try {
        tnSampleRate = Double.parseDouble(tnSampleRateRaw.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("tn3270.screenRenderSampleRate must be a number between 0.0 and 1.0", ex);
      }
    }
    if (tnSampleRate < 0d || tnSampleRate > 1d) {
      throw new IllegalArgumentException("tn3270.screenRenderSampleRate must be between 0.0 and 1.0");
    }
    String tnRedactionPolicy = kv.getOrDefault("tn3270.redaction.policy", defaults.tn3270RedactionPolicy());

    String kafkaBootstrapRaw = kv.get("kafkaBootstrap");
    String kafkaBootstrap = kafkaBootstrapRaw == null || kafkaBootstrapRaw.isBlank()
        ? null
        : Net.validateHostPort(kafkaBootstrapRaw);
    String kafkaTopicOverride = kv.get("kafkaTopicSegments");
    if (kafkaTopicOverride != null && !kafkaTopicOverride.isBlank()) {
      kafkaTopicSegments = Strings.sanitizeTopic("kafkaTopicSegments", kafkaTopicOverride);
    }

    int persistenceWorkers = parseBoundedInt(
        kv,
        "persistWorkers",
        defaults.persistenceWorkers(),
        MIN_PERSIST_WORKERS,
        MAX_PERSIST_WORKERS);
    int persistenceQueueCapacity = parseBoundedInt(
        kv,
        "persistQueueCapacity",
        defaults.persistenceQueueCapacity(),
        persistenceWorkers,
        MAX_QUEUE_CAPACITY);
    PersistenceQueueType persistenceQueueType =
        parsePersistenceQueueType(kv.get("persistQueueType"), defaults.persistenceQueueType());

    return new CaptureConfig(
        iface,
        pcapFile,
        filter,
        customBpf,
        protocol,
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
        persistenceQueueType,
        tnEmitRenders,
        tnSampleRate,
        tnRedactionPolicy);
  }

  /**
   * Parses CLI-style {@code key=value} arguments into a configuration (legacy helper).
   *
   * @param args CLI arguments array
   * @return parsed configuration
   * @throws IllegalArgumentException if parsing fails
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

  private static void denyDangerousBpf(String expression) {
    if (expression.contains(";") || expression.contains("`")) {
      throw new IllegalArgumentException("bpf expression contains disallowed characters");
    }
  }

  private static int parseBoundedInt(
      Map<String, String> kv, String key, int defaultValue, int min, int max) {
    String raw = kv.get(key);
    if (raw == null || raw.isBlank()) {
      Numbers.requireRange(key, defaultValue, min, max);
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      Numbers.requireRange(key, parsed, min, max);
      return parsed;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max, ex);
    }
  }

  private static boolean parseBoolean(String value, boolean fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Boolean.parseBoolean(value.trim());
  }

  private static Path parsePath(String name, String value) {
    try {
      return Path.of(Strings.requireNonBlank(name, value)).toAbsolutePath().normalize();
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException(name + " is not a valid path: " + value, ex);
    }
  }

  private static java.util.Optional<Path> parseOptionalPath(String name, String value) {
    if (value == null || value.isBlank()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(parsePath(name, value));
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

  private static Path normalizePath(String name, Path path) {
    if (path == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    String raw = path.toString();
    if (raw.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must not contain null bytes");
    }
    return path.toAbsolutePath().normalize();
  }

  private static Path defaultBaseDirectory() {
    String userHome = System.getProperty("user.home", ".");
    return Path.of(userHome, ".radar", "out");
  }

  /**\n   * Queue implementations available for live persistence hand-off.\n   * <p><strong>Role:</strong> Determines the blocking semantics of the persistence queue.</p>\n   * <p><strong>Performance:</strong> {@link #ARRAY} maps to {@link java.util.concurrent.ArrayBlockingQueue}, {@link #LINKED} to {@link java.util.concurrent.LinkedBlockingQueue}.</p>\n   * <p><strong>Observability:</strong> Metrics such as {@code capture.persist.queue.type} should reference the chosen constant.</p>\n   */
  public enum PersistenceQueueType {
    /** Array-backed bounded queue (high throughput, fixed capacity). */
    ARRAY,
    /** Linked queue supporting dynamic growth at the cost of extra allocations. */
    LINKED;

    /**
     * Parses a queue type string into a {@link PersistenceQueueType}.
     *
     * @param value textual representation (e.g., {@code array}, {@code linked}); must not be {@code null}
     * @return corresponding queue type
     * @throws IllegalArgumentException if the value is not recognized
     *
     * <p><strong>Concurrency:</strong> Thread-safe static helper.</p>
     * <p><strong>Performance:</strong> Normalizes input using lower-case conversion; O(n) in the input length.</p>
     * <p><strong>Observability:</strong> Callers should log invalid values for operator feedback.</p>
     */
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





















