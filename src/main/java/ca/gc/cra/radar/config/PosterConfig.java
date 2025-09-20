package ca.gc.cra.radar.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PosterConfig {
  private final IoMode ioMode;
  private final IoMode posterOutMode;
  private final Optional<String> kafkaBootstrap;
  private final Optional<ProtocolConfig> http;
  private final Optional<ProtocolConfig> tn3270;
  private final DecodeMode decodeMode;

  private PosterConfig(
      IoMode ioMode,
      IoMode posterOutMode,
      Optional<String> kafkaBootstrap,
      Optional<ProtocolConfig> http,
      Optional<ProtocolConfig> tn3270,
      DecodeMode decodeMode) {
    this.ioMode = Objects.requireNonNullElse(ioMode, IoMode.FILE);
    this.posterOutMode = Objects.requireNonNullElse(posterOutMode, IoMode.FILE);
    Optional<String> bootstrap = kafkaBootstrap == null ? Optional.empty() : kafkaBootstrap;
    this.kafkaBootstrap = bootstrap.map(String::trim).filter(s -> !s.isEmpty());
    this.http = http == null ? Optional.empty() : http;
    this.tn3270 = tn3270 == null ? Optional.empty() : tn3270;
    this.decodeMode = Objects.requireNonNull(decodeMode, "decodeMode");

    boolean kafkaInputConfigured = this.http.map(ProtocolConfig::hasKafkaInput).orElse(false)
        || this.tn3270.map(ProtocolConfig::hasKafkaInput).orElse(false)
        || this.ioMode == IoMode.KAFKA;
    boolean kafkaOutputConfigured = this.http.map(ProtocolConfig::hasKafkaOutput).orElse(false)
        || this.tn3270.map(ProtocolConfig::hasKafkaOutput).orElse(false)
        || this.posterOutMode == IoMode.KAFKA;

    if ((kafkaInputConfigured || kafkaOutputConfigured) && this.kafkaBootstrap.isEmpty()) {
      throw new IllegalArgumentException("kafkaBootstrap is required when Kafka input/output configured");
    }
  }

  public static PosterConfig fromMap(Map<String, String> args) {
    Objects.requireNonNull(args, "args");
    IoMode ioMode = IoMode.fromString(args.get("ioMode"));
    IoMode posterOutMode = IoMode.fromString(args.get("posterOutMode"));
    Optional<String> kafkaBootstrap = optionalString(args.get("kafkaBootstrap"));
    DecodeMode decodeMode = DecodeMode.fromString(args.getOrDefault("decode", "none"));

    ProtocolConfig httpConfig = buildProtocolConfig(
        args,
        new String[] {"httpIn", "--httpIn", "in", "--in"},
        new String[] {"httpOut", "--httpOut", "out", "--out"},
        optionalString(args.get("kafkaHttpPairsTopic")),
        optionalString(args.get("kafkaHttpReportsTopic")));

    ProtocolConfig tnConfig = buildProtocolConfig(
        args,
        new String[] {"tnIn", "--tnIn"},
        new String[] {"tnOut", "--tnOut", "out", "--out"},
        optionalString(args.get("kafkaTnPairsTopic")),
        optionalString(args.get("kafkaTnReportsTopic")));

    Optional<ProtocolConfig> http = Optional.ofNullable(httpConfig);
    Optional<ProtocolConfig> tn = Optional.ofNullable(tnConfig);

    if (http.isEmpty() && tn.isEmpty()) {
      throw new IllegalArgumentException("missing inputs: specify httpIn/tnIn or kafka topics");
    }

    if (ioMode == IoMode.FILE) {
      boolean kafkaInputConfigured = http.map(ProtocolConfig::hasKafkaInput).orElse(false)
          || tn.map(ProtocolConfig::hasKafkaInput).orElse(false);
      if (kafkaInputConfigured) {
        ioMode = IoMode.KAFKA;
      }
    }

    if (posterOutMode == IoMode.FILE) {
      boolean kafkaOutputConfigured = http.map(ProtocolConfig::hasKafkaOutput).orElse(false)
          || tn.map(ProtocolConfig::hasKafkaOutput).orElse(false);
      if (kafkaOutputConfigured) {
        posterOutMode = IoMode.KAFKA;
      }
    }

    IoMode finalPosterOutMode = posterOutMode;
    http.ifPresent(cfg -> validateOutput(cfg, finalPosterOutMode, "http"));
    tn.ifPresent(cfg -> validateOutput(cfg, finalPosterOutMode, "tn3270"));

    return new PosterConfig(ioMode, posterOutMode, kafkaBootstrap, http, tn, decodeMode);
  }

  private static void validateOutput(ProtocolConfig cfg, IoMode mode, String protocol) {
    if (mode == IoMode.FILE && cfg.outputDirectory().isEmpty()) {
      throw new IllegalArgumentException(protocol + "Out must be provided for FILE posterOutMode");
    }
    if (mode == IoMode.KAFKA && cfg.kafkaOutputTopic().isEmpty()) {
      throw new IllegalArgumentException("kafka" + protocol + "ReportsTopic must be provided for KAFKA posterOutMode");
    }
  }

  public IoMode ioMode() {
    return ioMode;
  }

  public IoMode posterOutMode() {
    return posterOutMode;
  }

  public Optional<String> kafkaBootstrap() {
    return kafkaBootstrap;
  }

  public Optional<ProtocolConfig> http() {
    return http;
  }

  public Optional<ProtocolConfig> tn3270() {
    return tn3270;
  }

  public DecodeMode decodeMode() {
    return decodeMode;
  }

  private static ProtocolConfig buildProtocolConfig(
      Map<String, String> args,
      String[] inputKeys,
      String[] outputKeys,
      Optional<String> kafkaInputOverride,
      Optional<String> kafkaOutputOverride) {

    Optional<String> inputValue = firstNonBlank(args, inputKeys);
    Optional<String> outputValue = firstNonBlank(args, outputKeys);

    Optional<String> kafkaInputTopic = kafkaTopic(inputValue).or(() -> kafkaInputOverride);
    Optional<Path> inputDir = kafkaInputTopic.isPresent() ? Optional.empty() : inputValue.map(PosterConfig::resolvePath);

    Optional<String> kafkaOutputTopic = kafkaTopic(outputValue).or(() -> kafkaOutputOverride);
    Optional<Path> outputDir;
    if (kafkaOutputTopic.isPresent()) {
      outputDir = Optional.empty();
    } else {
      outputDir = outputValue.map(PosterConfig::resolvePath);
    }

    boolean hasInput = inputDir.isPresent() || kafkaInputTopic.isPresent();
    if (!hasInput) {
      return null;
    }

    return new ProtocolConfig(inputDir, kafkaInputTopic, outputDir, kafkaOutputTopic);
  }

  private static Optional<String> firstNonBlank(Map<String, String> args, String[] keys) {
    for (String key : keys) {
      String value = args.get(key);
      if (value != null && !value.isBlank()) {
        return Optional.of(value.trim());
      }
    }
    return Optional.empty();
  }

  private static Optional<String> kafkaTopic(Optional<String> raw) {
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    String value = raw.get();
    if (value.startsWith("kafka:")) {
      String topic = value.substring("kafka:".length()).trim();
      if (topic.isEmpty()) {
        throw new IllegalArgumentException("Kafka topic must not be blank");
      }
      return Optional.of(topic);
    }
    return Optional.empty();
  }

  private static Optional<String> optionalString(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
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

  public record ProtocolConfig(
      Optional<Path> inputDirectory,
      Optional<String> kafkaInputTopic,
      Optional<Path> outputDirectory,
      Optional<String> kafkaOutputTopic) {
    public ProtocolConfig {
      inputDirectory = Objects.requireNonNullElseGet(inputDirectory, Optional::empty);
      kafkaInputTopic = Objects.requireNonNullElseGet(kafkaInputTopic, Optional::empty);
      outputDirectory = Objects.requireNonNullElseGet(outputDirectory, Optional::empty);
      kafkaOutputTopic = Objects.requireNonNullElseGet(kafkaOutputTopic, Optional::empty);
    }

    public boolean hasKafkaInput() {
      return kafkaInputTopic.isPresent();
    }

    public boolean hasKafkaOutput() {
      return kafkaOutputTopic.isPresent();
    }

    public Optional<Path> inputDirectory() {
      return inputDirectory;
    }

    public Optional<String> kafkaInputTopic() {
      return kafkaInputTopic;
    }

    public Optional<Path> outputDirectory() {
      return outputDirectory;
    }

    public Optional<String> kafkaOutputTopic() {
      return kafkaOutputTopic;
    }

    public Path requireInputDirectory() {
      return inputDirectory.orElseThrow(() -> new IllegalStateException("file input not configured"));
    }

    public String requireKafkaInputTopic() {
      return kafkaInputTopic.orElseThrow(() -> new IllegalStateException("kafka input not configured"));
    }

    public Path requireOutputDirectory() {
      return outputDirectory.orElseThrow(() -> new IllegalStateException("file output not configured"));
    }

    public String requireKafkaOutputTopic() {
      return kafkaOutputTopic.orElseThrow(() -> new IllegalStateException("kafka output not configured"));
    }
  }

  public enum DecodeMode {
    NONE,
    TRANSFER,
    ALL;

    public static DecodeMode fromString(String value) {
      if (value == null) {
        return NONE;
      }
      switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "none" -> {
          return NONE;
        }
        case "transfer" -> {
          return TRANSFER;
        }
        case "all" -> {
          return ALL;
        }
        default -> throw new IllegalArgumentException("unknown decode mode: " + value);
      }
    }

    public boolean decodeTransferEncoding() {
      return this == TRANSFER || this == ALL;
    }

    public boolean decodeContentEncoding() {
      return this == ALL;
    }
  }
}

