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
 * Configuration for poster pipelines that render reconstructed protocol traffic.
 * <p>Aggregates per-protocol sources and sinks along with Kafka wiring and decode behavior. Immutable
 * and thread-safe.</p>
 *
 * @since RADAR 0.1-doc
 */
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

  /**
   * Builds a poster configuration from CLI-style key/value pairs.
   *
   * @param args argument map; must not be {@code null}
   * @return populated poster configuration
   * @throws IllegalArgumentException if required inputs or outputs are missing or invalid
   * @since RADAR 0.1-doc
   */
  public static PosterConfig fromMap(Map<String, String> args) {
    Objects.requireNonNull(args, "args");
    IoMode ioMode = IoMode.fromString(args.get("ioMode"));
    IoMode posterOutMode = IoMode.fromString(args.get("posterOutMode"));
    Optional<String> kafkaBootstrap = optionalString(args.get("kafkaBootstrap")).map(Net::validateHostPort);
    DecodeMode decodeMode = DecodeMode.fromString(args.getOrDefault("decode", "none"));

    ProtocolConfig httpConfig = buildProtocolConfig(
        args,
        "http",
        new String[] {"httpIn", "--httpIn", "in", "--in"},
        new String[] {"httpOut", "--httpOut", "out", "--out"},
        optionalString(args.get("kafkaHttpPairsTopic")),
        optionalString(args.get("kafkaHttpReportsTopic")));

    ProtocolConfig tnConfig = buildProtocolConfig(
        args,
        "tn3270",
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

  /**
   * Returns the IO mode determining input strategy for poster pipelines.
   *
   * @return IO mode determining input strategy for poster pipelines
   * @since RADAR 0.1-doc
   */
  public IoMode ioMode() {
    return ioMode;
  }

  /**
   * @return IO mode describing where rendered posters should be written
   * @since RADAR 0.1-doc
   */
  public IoMode posterOutMode() {
    return posterOutMode;
  }

  /**
   * @return Kafka bootstrap servers if Kafka IO is in use
   * @since RADAR 0.1-doc
   */
  public Optional<String> kafkaBootstrap() {
    return kafkaBootstrap;
  }

  /**
   * @return HTTP protocol configuration when enabled
   * @since RADAR 0.1-doc
   */
  public Optional<ProtocolConfig> http() {
    return http;
  }

  /**
   * @return TN3270 protocol configuration when enabled
   * @since RADAR 0.1-doc
   */
  public Optional<ProtocolConfig> tn3270() {
    return tn3270;
  }

  /**
   * @return decode mode instructing poster pipelines how aggressively to decode bodies
   * @since RADAR 0.1-doc
   */
  public DecodeMode decodeMode() {
    return decodeMode;
  }

  private static ProtocolConfig buildProtocolConfig(
      Map<String, String> args,
      String protocol,
      String[] inKeys,
      String[] outKeys,
      Optional<String> kafkaPairsTopic,
      Optional<String> kafkaReportsTopic) {
    Optional<String> inRaw = firstNonBlank(args, inKeys);
    Optional<String> outRaw = firstNonBlank(args, outKeys);

    Optional<Path> inputDir = Optional.empty();
    Optional<String> kafkaInputTopic = Optional.empty();

    if (inRaw.isPresent()) {
      String value = inRaw.get();
      if (value.startsWith("kafka:")) {
        String topic = value.substring("kafka:".length());
        kafkaInputTopic = Optional.of(Strings.sanitizeTopic(protocol + "KafkaInputTopic", topic));
      } else {
        inputDir = Optional.of(resolvePath(protocol + "In", value));
      }
    } else if (kafkaPairsTopic.isPresent()) {
      kafkaInputTopic = Optional.of(
          Strings.sanitizeTopic(protocol + "KafkaPairsTopic", kafkaPairsTopic.get()));
    }

    Optional<Path> outputDir = Optional.empty();
    Optional<String> kafkaOutputTopic = Optional.empty();

    if (outRaw.isPresent()) {
      String value = outRaw.get();
      if (value.startsWith("kafka:")) {
        String topic = value.substring("kafka:".length());
        kafkaOutputTopic = Optional.of(
            Strings.sanitizeTopic(protocol + "KafkaOutputTopic", topic));
      } else {
        outputDir = Optional.of(resolvePath(protocol + "Out", value));
      }
    } else if (kafkaReportsTopic.isPresent()) {
      kafkaOutputTopic = Optional.of(
          Strings.sanitizeTopic(protocol + "KafkaReportsTopic", kafkaReportsTopic.get()));
    }

    if (inputDir.isEmpty() && kafkaInputTopic.isEmpty()) {
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

  private static Optional<String> optionalString(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  private static Path resolvePath(String name, String value) {
    try {
      return Path.of(Strings.requireNonBlank(name, value)).toAbsolutePath().normalize();
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException(name + " is not a valid path: " + value, ex);
    }
  }

  /**
   * Per-protocol poster configuration covering file and Kafka targets.
   *
   * @param inputDirectory directory supplying message pairs when using file IO
   * @param kafkaInputTopic Kafka topic supplying message pairs when using Kafka IO
   * @param outputDirectory directory for poster outputs when writing to the file system
   * @param kafkaOutputTopic Kafka topic for rendered posters when using Kafka outputs
   * @since RADAR 0.1-doc
   */
  public record ProtocolConfig(
      Optional<Path> inputDirectory,
      Optional<String> kafkaInputTopic,
      Optional<Path> outputDirectory,
      Optional<String> kafkaOutputTopic) {
    /**
     * Normalizes protocol-specific configuration values.
     *
     * @since RADAR 0.1-doc
     */
    public ProtocolConfig {
      inputDirectory = inputDirectory == null ? Optional.empty()
          : inputDirectory.map(path -> path.toAbsolutePath().normalize());
      kafkaInputTopic = kafkaInputTopic == null ? Optional.empty() : kafkaInputTopic;
      outputDirectory = outputDirectory == null ? Optional.empty()
          : outputDirectory.map(path -> path.toAbsolutePath().normalize());
      kafkaOutputTopic = kafkaOutputTopic == null ? Optional.empty() : kafkaOutputTopic;
    }

    /**
     * @return {@code true} when Kafka should be used as the input source
     * @since RADAR 0.1-doc
     */
    public boolean hasKafkaInput() {
      return kafkaInputTopic.isPresent();
    }

    /**
     * @return {@code true} when Kafka should be used as the output sink
     * @since RADAR 0.1-doc
     */
    public boolean hasKafkaOutput() {
      return kafkaOutputTopic.isPresent();
    }

    /**
     * @return file-system input directory if configured
     * @since RADAR 0.1-doc
     */
    public Optional<Path> inputDirectory() {
      return inputDirectory;
    }

    /**
     * Returns the Kafka input topic if configured.
     *
     * @return Kafka input topic if configured
     * @since RADAR 0.1-doc
     */
    public Optional<String> kafkaInputTopic() {
      return kafkaInputTopic;
    }

    /**
     * Returns the file-system output directory if configured.
     *
     * @return file-system output directory if configured
     * @since RADAR 0.1-doc
     */
    public Optional<Path> outputDirectory() {
      return outputDirectory;
    }

    /**
     * Returns the Kafka output topic if configured.
     *
     * @return Kafka output topic if configured
     * @since RADAR 0.1-doc
     */
    public Optional<String> kafkaOutputTopic() {
      return kafkaOutputTopic;
    }

    /**
     * Returns the configured file input directory or throws when absent.
     *
     * @return input directory
     * @throws IllegalStateException if file IO is not configured
     * @since RADAR 0.1-doc
     */
    public Path requireInputDirectory() {
      return inputDirectory.orElseThrow(() -> new IllegalStateException("file input not configured"));
    }

    /**
     * Returns the configured Kafka input topic or throws when absent.
     *
     * @return Kafka topic
     * @throws IllegalStateException if Kafka input is not configured
     * @since RADAR 0.1-doc
     */
    public String requireKafkaInputTopic() {
      return kafkaInputTopic.orElseThrow(() -> new IllegalStateException("kafka input not configured"));
    }

    /**
     * Returns the configured file output directory or throws when absent.
     *
     * @return output directory
     * @throws IllegalStateException if file output is not configured
     * @since RADAR 0.1-doc
     */
    public Path requireOutputDirectory() {
      return outputDirectory.orElseThrow(() -> new IllegalStateException("file output not configured"));
    }

    /**
     * Returns the configured Kafka output topic or throws when absent.
     *
     * @return Kafka topic
     * @throws IllegalStateException if Kafka output is not configured
     * @since RADAR 0.1-doc
     */
    public String requireKafkaOutputTopic() {
      return kafkaOutputTopic.orElseThrow(() -> new IllegalStateException("kafka output not configured"));
    }
  }

  /**
   * Controls how aggressively poster pipelines decode transfer/content encodings.
   *
   * @since RADAR 0.1-doc
   */
  public enum DecodeMode {
    /** Skip all decoding; emit raw payloads. */
    NONE,
    /** Decode transfer encodings (chunked, etc.) but preserve content encodings. */
    TRANSFER,
    /** Decode both transfer and content encodings. */
    ALL;

    /**
     * Parses textual CLI input into a decode mode.
     *
     * @param value string such as {@code "none"}, {@code "transfer"}, or {@code "all"}
     * @return decode mode
     * @throws IllegalArgumentException if value is unknown
     * @since RADAR 0.1-doc
     */
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

    /**
     * @return {@code true} if transfer encodings (e.g., chunked) should be decoded
     * @since RADAR 0.1-doc
     */
    public boolean decodeTransferEncoding() {
      return this == TRANSFER || this == ALL;
    }

    /**
     * @return {@code true} if content encodings (e.g., gzip) should be decoded
     * @since RADAR 0.1-doc
     */
    public boolean decodeContentEncoding() {
      return this == ALL;
    }
  }
}








