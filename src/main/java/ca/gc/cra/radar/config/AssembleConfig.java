package ca.gc.cra.radar.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

  public AssembleConfig {
    ioMode = Objects.requireNonNullElse(ioMode, IoMode.FILE);
    Optional<String> bootstrap = kafkaBootstrap == null ? Optional.empty() : kafkaBootstrap;
    kafkaBootstrap = bootstrap.map(String::trim).filter(s -> !s.isEmpty());
    kafkaHttpPairsTopic = sanitizeTopic(kafkaHttpPairsTopic, "kafkaHttpPairsTopic");
    kafkaTnPairsTopic = sanitizeTopic(kafkaTnPairsTopic, "kafkaTnPairsTopic");
    Objects.requireNonNull(inputDirectory, "inputDirectory");
    Objects.requireNonNull(outputDirectory, "outputDirectory");
    httpOutputDirectory = sanitizeOptional(httpOutputDirectory);
    tnOutputDirectory = sanitizeOptional(tnOutputDirectory);

    if (!httpEnabled && !tnEnabled) {
      throw new IllegalArgumentException("At least one protocol must be enabled");
    }
    if (ioMode == IoMode.KAFKA && kafkaBootstrap.isEmpty()) {
      throw new IllegalArgumentException("kafkaBootstrap is required when ioMode=KAFKA");
    }
  }

  public static AssembleConfig defaults() {
    return new AssembleConfig(
        IoMode.FILE,
        Optional.empty(),
        "radar.segments",
        "radar.http.pairs",
        "radar.tn3270.pairs",
        Path.of("./cap-out"),
        Path.of("./pairs-out"),
        true,
        false,
        Optional.empty(),
        Optional.empty());
  }

  public static AssembleConfig fromMap(Map<String, String> options) {
    AssembleConfig defaults = defaults();
    IoMode ioMode = parseIoMode(options.get("ioMode"), defaults.ioMode());
    Optional<String> kafkaBootstrap = optionalString(options.get("kafkaBootstrap"));

    Path input = defaults.inputDirectory();
    String kafkaSegmentsTopic = defaults.kafkaSegmentsTopic();
    String inRaw = firstNonBlank(options.get("in"), options.get("segments"));
    if (inRaw != null && !inRaw.isBlank()) {
      if (inRaw.startsWith("kafka:")) {
        ioMode = IoMode.KAFKA;
        kafkaSegmentsTopic = sanitizeTopic(inRaw.substring("kafka:".length()), "kafkaSegmentsTopic");
      } else {
        input = resolvePath(inRaw);
      }
    }
    String segmentsTopicOverride = optionalString(options.get("kafkaSegmentsTopic"))
        .orElse(kafkaSegmentsTopic);
    kafkaSegmentsTopic = segmentsTopicOverride;

    Path output = resolvePath(
        firstNonBlank(options.get("out"), options.get("--out"), defaults.outputDirectory().toString()));
    boolean httpEnabled = parseBoolean(options.get("httpEnabled"), true);
    boolean tnEnabled = parseBoolean(options.get("tnEnabled"), false);

    Optional<Path> httpOut = optionalPath(firstNonBlank(options.get("httpOut"), options.get("--httpOut")));
    Optional<Path> tnOut = optionalPath(firstNonBlank(options.get("tnOut"), options.get("--tnOut")));

    String kafkaHttpPairsTopic = optionalString(options.get("kafkaHttpPairsTopic"))
        .orElse(defaults.kafkaHttpPairsTopic());
    String kafkaTnPairsTopic = optionalString(options.get("kafkaTnPairsTopic"))
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

  public Path effectiveHttpOut() {
    return httpOutputDirectory.orElseGet(() -> outputDirectory.resolve("http"));
  }

  public Path effectiveTnOut() {
    return tnOutputDirectory.orElseGet(() -> outputDirectory.resolve("tn3270"));
  }

  private static boolean parseBoolean(String value, boolean defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
  }

  private static Path resolvePath(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Path value must not be blank");
    }
    try {
      return Path.of(value);
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException("Invalid path: " + value, ex);
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return values.length == 0 ? "" : values[values.length - 1];
  }

  private static Optional<Path> optionalPath(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(resolvePath(value));
  }

  private static Optional<Path> sanitizeOptional(Optional<Path> candidate) {
    if (candidate == null) {
      return Optional.empty();
    }
    return candidate.map(Path::normalize);
  }

  private static Optional<String> optionalString(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  private static IoMode parseIoMode(String value, IoMode fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return IoMode.fromString(value);
  }

  private static String sanitizeTopic(String topic, String name) {
    if (topic == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    String trimmed = topic.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return trimmed;
  }
}
