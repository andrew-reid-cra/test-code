package ca.gc.cra.radar.config;

import ca.gc.cra.radar.validation.Net;
import ca.gc.cra.radar.validation.Strings;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for the assemble CLI pipeline.
 *
 * @param ioMode input mode ({@link IoMode#FILE} or {@link IoMode#KAFKA})
 * @param kafkaBootstrap Kafka bootstrap servers when Kafka is used
 * @param kafkaSegmentsTopic topic supplying captured segments in Kafka mode
 * @param kafkaHttpPairsTopic topic receiving HTTP pairs in Kafka mode
 * @param kafkaTnPairsTopic topic receiving TN3270 pairs in Kafka mode
 * @param inputDirectory directory containing serialized segment files when in file mode
 * @param outputDirectory root directory for assembled outputs
 * @param httpEnabled whether HTTP reconstruction is enabled
 * @param tnEnabled whether TN3270 reconstruction is enabled
 * @param httpOutputDirectory optional override for HTTP output directory
 * @param tnOutputDirectory optional override for TN3270 output directory
 * @since RADAR 0.1-doc
 */
public record AssembleConfig(
    IoMode ioMode,
    Optional<String> kafkaBootstrap,
    String kafkaSegmentsTopic,
    String kafkaHttpPairsTopic,
    String kafkaTnPairsTopic,
    Path inputDirectory,
    Path outputDirectory,
    boolean httpEnabled,
    boolean tnEnabled,
    Optional<Path> httpOutputDirectory,
    Optional<Path> tnOutputDirectory) {

  private static final Path DEFAULT_BASE = defaultBaseDirectory();

  /**
   * Normalizes assemble configuration values.
   *
   * @since RADAR 0.1-doc
   */
  public AssembleConfig {
    ioMode = Objects.requireNonNullElse(ioMode, IoMode.FILE);
    kafkaBootstrap = sanitizeBootstrap(kafkaBootstrap);
    kafkaSegmentsTopic = Strings.sanitizeTopic("kafkaSegmentsTopic", kafkaSegmentsTopic);
    kafkaHttpPairsTopic = Strings.sanitizeTopic("kafkaHttpPairsTopic", kafkaHttpPairsTopic);
    kafkaTnPairsTopic = Strings.sanitizeTopic("kafkaTnPairsTopic", kafkaTnPairsTopic);
    inputDirectory = normalizePath("inputDirectory", inputDirectory);
    outputDirectory = normalizePath("outputDirectory", outputDirectory);
    httpOutputDirectory = sanitizeOptionalPath("httpOutputDirectory", httpOutputDirectory);
    tnOutputDirectory = sanitizeOptionalPath("tnOutputDirectory", tnOutputDirectory);

    if (!httpEnabled && !tnEnabled) {
      throw new IllegalArgumentException("At least one protocol must be enabled");
    }
    if (ioMode == IoMode.KAFKA && kafkaBootstrap.isEmpty()) {
      throw new IllegalArgumentException("kafkaBootstrap is required when ioMode=KAFKA");
    }
  }

  /**
   * Returns a baseline configuration pointing at local directories.
   *
   * @return default assemble configuration
   * @since RADAR 0.1-doc
   */
  public static AssembleConfig defaults() {
    return new AssembleConfig(
        IoMode.FILE,
        Optional.empty(),
        "radar.segments",
        "radar.http.pairs",
        "radar.tn3270.pairs",
        DEFAULT_BASE.resolve("capture").resolve("segments"),
        DEFAULT_BASE.resolve("assemble"),
        true,
        false,
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Creates a configuration instance from CLI-style key/value pairs.
   *
   * @param options key/value pairs such as {@code in}, {@code out}, {@code kafkaBootstrap}
   * @return populated configuration
   * @throws IllegalArgumentException when values are invalid or required settings are missing
   * @since RADAR 0.1-doc
   */
  public static AssembleConfig fromMap(Map<String, String> options) {
    Objects.requireNonNull(options, "options");
    AssembleConfig defaults = defaults();

    IoMode ioMode = parseIoMode(options.get("ioMode"), defaults.ioMode());
    Optional<String> kafkaBootstrap = sanitizeBootstrap(optionalString(options.get("kafkaBootstrap")));

    Path input = defaults.inputDirectory();
    String kafkaSegmentsTopic = defaults.kafkaSegmentsTopic();
    String inRaw = firstNonBlank(options, "in", "segments");
    if (inRaw != null) {
      if (inRaw.startsWith("kafka:")) {
        ioMode = IoMode.KAFKA;
        kafkaSegmentsTopic = Strings.sanitizeTopic("kafkaSegmentsTopic", inRaw.substring("kafka:".length()));
      } else {
        input = parsePath("in", inRaw);
      }
    }
    String kafkaSegmentsOverride = options.get("kafkaSegmentsTopic");
    if (kafkaSegmentsOverride != null && !kafkaSegmentsOverride.isBlank()) {
      kafkaSegmentsTopic = Strings.sanitizeTopic("kafkaSegmentsTopic", kafkaSegmentsOverride);
    }

    String outOverride = firstNonBlank(options, "out", "--out");
    Path output = outOverride == null ? defaults.outputDirectory() : parsePath("out", outOverride);
    boolean httpEnabled = parseBoolean(options.get("httpEnabled"), true);
    boolean tnEnabled = parseBoolean(options.get("tnEnabled"), false);

    Optional<Path> httpOut = optionalPath("httpOut", firstNonBlank(options, "httpOut", "--httpOut"));
    Optional<Path> tnOut = optionalPath("tnOut", firstNonBlank(options, "tnOut", "--tnOut"));

    String kafkaHttpPairsTopic = optionalString(options.get("kafkaHttpPairsTopic"))
        .map(value -> Strings.sanitizeTopic("kafkaHttpPairsTopic", value))
        .orElse(defaults.kafkaHttpPairsTopic());
    String kafkaTnPairsTopic = optionalString(options.get("kafkaTnPairsTopic"))
        .map(value -> Strings.sanitizeTopic("kafkaTnPairsTopic", value))
        .orElse(defaults.kafkaTnPairsTopic());

    if (!httpEnabled && !tnEnabled) {
      throw new IllegalArgumentException("At least one protocol must be enabled");
    }

    if (ioMode == IoMode.KAFKA && kafkaBootstrap.isEmpty()) {
      throw new IllegalArgumentException("kafkaBootstrap is required for KAFKA ioMode");
    }

    return new AssembleConfig(
        ioMode,
        kafkaBootstrap,
        kafkaSegmentsTopic,
        kafkaHttpPairsTopic,
        kafkaTnPairsTopic,
        input,
        output,
        httpEnabled,
        tnEnabled,
        httpOut,
        tnOut);
  }

  /**
   * Resolves the effective HTTP output directory, defaulting under {@link #outputDirectory}.
   *
   * @return directory for HTTP output
   * @since RADAR 0.1-doc
   */
  public Path effectiveHttpOut() {
    return httpOutputDirectory.orElseGet(() -> outputDirectory.resolve("http"));
  }

  /**
   * Resolves the effective TN3270 output directory, defaulting under {@link #outputDirectory}.
   *
   * @return directory for TN3270 output
   * @since RADAR 0.1-doc
   */
  public Path effectiveTnOut() {
    return tnOutputDirectory.orElseGet(() -> outputDirectory.resolve("tn3270"));
  }

  private static boolean parseBoolean(String value, boolean defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value.trim());
  }

  private static Optional<String> optionalString(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  private static Optional<String> sanitizeBootstrap(Optional<String> candidate) {
    if (candidate == null) {
      return Optional.empty();
    }
    return candidate.map(Net::validateHostPort);
  }

  private static Optional<Path> optionalPath(String name, String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(parsePath(name, value));
  }

  private static Optional<Path> sanitizeOptionalPath(String name, Optional<Path> candidate) {
    if (candidate == null || candidate.isEmpty()) {
      return Optional.empty();
    }
    return candidate.map(path -> normalizePath(name, path));
  }

  private static Path parsePath(String name, String value) {
    try {
      return Path.of(Strings.requireNonBlank(name, value)).toAbsolutePath().normalize();
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException(name + " is not a valid path: " + value, ex);
    }
  }

  private static Path normalizePath(String name, Path path) {
    Objects.requireNonNull(path, name + " must not be null");
    String raw = path.toString();
    if (raw.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must not contain null bytes");
    }
    return path.toAbsolutePath().normalize();
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

  private static IoMode parseIoMode(String value, IoMode fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return IoMode.fromString(value.trim());
  }

  private static Path defaultBaseDirectory() {
    String home = System.getProperty("user.home", ".");
    return Path.of(home, ".radar", "out");
  }
}

