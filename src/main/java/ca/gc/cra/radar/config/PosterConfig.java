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
 * <strong>What:</strong> Centralizes poster pipeline IO and protocol configuration knobs.
 * <p><strong>Why:</strong> Keeps CLI-driven poster runs reproducible by normalizing file/Kafka options and decode behaviour across protocols.</p>
 * <p><strong>Role:</strong> Adapter configuration aggregate for the sink stage (poster rendering CLI).</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Determine IO strategies (file directories or Kafka topics) per supported protocol.</li>
 *   <li>Expose decode preferences so poster pipelines know how to transform payloads.</li>
 *   <li>Enforce Kafka bootstrap requirements whenever any poster input/output uses Kafka.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Immutable once constructed; safe for concurrent reads.</p>
 * <p><strong>Performance:</strong> Performs validation at creation; accessors are constant-time lookups.</p>
 * <p><strong>Observability:</strong> Values should be surfaced in logs/metrics (e.g., {@code poster.ioMode}) when instantiating pipelines.</p>
 *
 * @implNote Ensures Kafka bootstrap servers are supplied whenever inputs or outputs rely on Kafka topics and sanitizes supplied directory/topic values.
 * @since 0.1.0
 * @see ca.gc.cra.radar.application.port.poster.PosterPipeline
 * @see ca.gc.cra.radar.config.AssembleConfig
 */
public final class PosterConfig {
  private final IoMode ioMode;
  private final IoMode posterOutMode;
  private final Optional<String> kafkaBootstrap;
  private final Optional<ProtocolConfig> http;
  private final Optional<ProtocolConfig> tn3270;
  private final DecodeMode decodeMode;

  /**
   * Normalizes poster configuration values and enforces cross-field invariants.
   *
   * @param ioMode configured capture IO mode; {@code null} defaults to {@link IoMode#FILE}
   * @param posterOutMode configured poster output IO mode; {@code null} defaults to {@link IoMode#FILE}
   * @param kafkaBootstrap optional Kafka bootstrap servers in {@code host:port[,host:port]} format
   * @param http optional HTTP protocol configuration; absent when HTTP posters are disabled
   * @param tn3270 optional TN3270 protocol configuration; absent when TN3270 posters are disabled
   * @param decodeMode decode preference describing how aggressively payloads should be transformed
   * @throws IllegalArgumentException if Kafka topics are used without bootstrap servers
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Invoked during bootstrap on a single thread; not thread-safe for concurrent mutation.</p>
   * <p><strong>Performance:</strong> Executes constant-time normalization and validation for supplied values.</p>
   * <p><strong>Observability:</strong> Throws descriptive exceptions so CLI callers can log misconfiguration details.</p>
   */
  private PosterConfig(
      IoMode ioMode,
      IoMode posterOutMode,
      Optional<String> kafkaBootstrap,
      Optional<ProtocolConfig> http,
      Optional<ProtocolConfig> tn3270,
      DecodeMode decodeMode) {
    this.ioMode = Objects.requireNonNullElse(ioMode, IoMode.FILE);
    this.posterOutMode = Objects.requireNonNullElse(posterOutMode, IoMode.FILE);
    Optional<String> bootstrap = Objects.requireNonNullElse(kafkaBootstrap, Optional.empty());
    this.kafkaBootstrap = bootstrap.map(String::trim).filter(s -> !s.isEmpty());
    this.http = Objects.requireNonNullElse(http, Optional.empty());
    this.tn3270 = Objects.requireNonNullElse(tn3270, Optional.empty());
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
   * @param args argument map (CLI flags/environment); must not be {@code null}
   * @return normalized poster configuration ready for poster pipelines
   * @throws IllegalArgumentException if required inputs or outputs are missing or invalid
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Safe for single-threaded use; callers must not mutate {@code args} during parsing.</p>
   * <p><strong>Performance:</strong> Resolves paths and sanitizes topics in O(n) relative to provided entries.</p>
   * <p><strong>Observability:</strong> Validation failures should be surfaced to operators; exception messages contain offending keys.</p>
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

  /**
   * Validates that protocol-specific output matches the global poster output mode.
   *
   * @param cfg protocol configuration under evaluation; must not be {@code null}
   * @param mode effective global poster output mode
   * @param protocol short protocol identifier used in error messages
   * @throws IllegalArgumentException if the protocol lacks the required output for the selected IO mode
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure function; safe to call concurrently.</p>
   * <p><strong>Performance:</strong> Constant-time checks on cached optionals.</p>
   * <p><strong>Observability:</strong> Exception messages surface the missing knob so CLI wrappers can log actionable guidance.</p>
   */
  private static void validateOutput(ProtocolConfig cfg, IoMode mode, String protocol) {
    if (mode == IoMode.FILE && cfg.outputDirectory().isEmpty()) {
      throw new IllegalArgumentException(protocol + "Out must be provided for FILE posterOutMode");
    }
    if (mode == IoMode.KAFKA && cfg.kafkaOutputTopic().isEmpty()) {
      throw new IllegalArgumentException("kafka" + protocol + "ReportsTopic must be provided for KAFKA posterOutMode");
    }
  }

  /**
   * Returns how poster pipelines should read reconstructed pairs.
   *
   * @return IO mode describing poster pipeline inputs
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor on an immutable object.</p>
   * <p><strong>Performance:</strong> Constant-time getter.</p>
   * <p><strong>Observability:</strong> Use to tag metrics/log statements (e.g., {@code poster.ioMode}).</p>
   */
  public IoMode ioMode() {
    return ioMode;
  }

  /**
   * Returns how poster output artifacts should be delivered.
   *
   * @return IO mode describing the poster output sink
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor.</p>
   * <p><strong>Performance:</strong> Constant-time getter.</p>
   * <p><strong>Observability:</strong> Feed into logs/metrics differentiating file versus Kafka outputs.</p>
   */
  public IoMode posterOutMode() {
    return posterOutMode;
  }

  /**
   * Returns the Kafka bootstrap servers required for Kafka-backed poster IO.
   *
   * @return optional Kafka bootstrap string in {@code host:port[,host:port]} form
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Safe to call concurrently; returns a stable {@link Optional} view.</p>
   * <p><strong>Performance:</strong> Constant-time getter.</p>
   * <p><strong>Observability:</strong> Should be redacted before logging; expose only sanitized connection hints.</p>
   */
  public Optional<String> kafkaBootstrap() {
    return kafkaBootstrap;
  }

  /**
   * Returns the HTTP protocol configuration, if posters for HTTP are enabled.
   *
   * @return optional HTTP configuration describing inputs and outputs
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Immutable optional view safe for concurrent readers.</p>
   * <p><strong>Performance:</strong> Constant-time getter.</p>
   * <p><strong>Observability:</strong> When present, callers should describe configured directories/topics in debug logs.</p>
   */
  public Optional<ProtocolConfig> http() {
    return http;
  }

  /**
   * Returns the TN3270 protocol configuration, if posters for TN3270 are enabled.
   *
   * @return optional TN3270 configuration describing inputs and outputs
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Immutable optional view.</p>
   * <p><strong>Performance:</strong> Constant-time getter.</p>
   * <p><strong>Observability:</strong> When present, include the configured directories/topics in debug/trace logs.</p>
   */
  public Optional<ProtocolConfig> tn3270() {
    return tn3270;
  }

  /**
   * Returns the decode mode instructing poster pipelines how aggressively to transform payloads.
   *
   * @return decode strategy for payload handling
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor returning an enum.</p>
   * <p><strong>Performance:</strong> Constant-time getter.</p>
   * <p><strong>Observability:</strong> Include in logs to trace poster decode decisions.</p>
   */
  public DecodeMode decodeMode() {
    return decodeMode;
  }

  /**
   * Derives protocol-specific poster configuration based on CLI keys.
   *
   * @param args merged CLI keys; must not be {@code null}
   * @param protocol lowercase protocol name (e.g., {@code "http"}) used for error messages
   * @param inKeys ordered key synonyms describing possible inputs
   * @param outKeys ordered key synonyms describing possible outputs
   * @param kafkaPairsTopic optional Kafka topic carrying reconstructed pairs
   * @param kafkaReportsTopic optional Kafka topic carrying rendered reports
   * @return protocol configuration or {@code null} when the protocol is not configured
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure helper; safe for concurrent invocation.</p>
   * <p><strong>Performance:</strong> Scans provided key aliases once and performs constant-time sanitization.</p>
   * <p><strong>Observability:</strong> Leverages {@link Strings#sanitizeTopic(String, String)} to enforce logging-friendly names.</p>
   */
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

  /**
   * Finds the first non-blank argument value from a list of synonymous keys.
   *
   * @param args CLI arguments map; must not be {@code null}
   * @param keys ordered list of key aliases to inspect
   * @return optional trimmed value
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure helper; callers may use it concurrently.</p>
   * <p><strong>Performance:</strong> Linear in {@code keys.length}; each lookup is constant-time.</p>
   * <p><strong>Observability:</strong> Preserves the raw value for downstream validation so errors can cite the original key.</p>
   */
  private static Optional<String> firstNonBlank(Map<String, String> args, String[] keys) {
    for (String key : keys) {
      String value = args.get(key);
      if (value != null && !value.isBlank()) {
        return Optional.of(value.trim());
      }
    }
    return Optional.empty();
  }

  /**
   * Converts possibly blank strings into {@link Optional} values.
   *
   * @param value candidate string; {@code null} yields {@link Optional#empty()}
   * @return optional containing the trimmed value when non-blank
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure helper; thread-safe.</p>
   * <p><strong>Performance:</strong> Constant-time trimming and length checks.</p>
   * <p><strong>Observability:</strong> Avoids logging accidental whitespace-only parameters.</p>
   */
  private static Optional<String> optionalString(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  /**
   * Resolves and normalizes a filesystem path while surfacing invalid input.
   *
   * @param name logical parameter name used in diagnostics
   * @param value raw path string supplied by operators
   * @return normalized absolute path
   * @throws IllegalArgumentException if the path is blank or syntactically invalid
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure helper without shared state.</p>
   * <p><strong>Performance:</strong> Relies on {@link Path#of(String, String...)}; cost dominated by JDK path parsing.</p>
   * <p><strong>Observability:</strong> Exception messages include the offending value for audit logs.</p>
   */
  private static Path resolvePath(String name, String value) {
    try {
      return Path.of(Strings.requireNonBlank(name, value)).toAbsolutePath().normalize();
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException(name + " is not a valid path: " + value, ex);
    }
  }

  /**
   * <strong>What:</strong> Protocol-specific configuration for poster inputs and outputs.
   * <p><strong>Why:</strong> Encapsulates directories and Kafka topics so each protocol can wire itself without repeating parsing logic.</p>
   * <p><strong>Role:</strong> Adapter configuration record managed by {@link PosterConfig} for the sink stage.</p>
   * <p><strong>Responsibilities:</strong>
   * <ul>
   *   <li>Expose optional file directories used for ingesting reconstructed pairs and emitting rendered reports.</li>
   *   <li>Expose optional Kafka topics used when pipelines stream pairs or reports.</li>
   *   <li>Offer helpers that tell caller code whether Kafka/file IO is active.</li>
   * </ul>
   * <p><strong>Thread-safety:</strong> Immutable record; safe for concurrent reads.</p>
   * <p><strong>Performance:</strong> Normalizes paths during construction; accessors are constant-time.</p>
   * <p><strong>Observability:</strong> Values should be logged (sanitized) when wiring poster adapters.</p>
   *
   * @param inputDirectory directory supplying message pairs when using file IO
   * @param kafkaInputTopic Kafka topic supplying message pairs when using Kafka IO
   * @param outputDirectory directory for poster outputs when writing to the file system
   * @param kafkaOutputTopic Kafka topic for rendered posters when using Kafka outputs
   * @since RADAR 0.1-doc
   * @implNote Optional values are left untouched beyond normalization so downstream adapters can perform protocol-specific validation.
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
     *
     * <p><strong>Concurrency:</strong> Thread-safe normalization producing an immutable record.</p>
     * <p><strong>Performance:</strong> O(1) path/topic sanitation.</p>
     * <p><strong>Observability:</strong> Trusts upstream sanitizers so diagnostics reference the original knob names.</p>
     */
    public ProtocolConfig {
      inputDirectory = Objects.requireNonNull(inputDirectory, "inputDirectory")
          .map(path -> path.toAbsolutePath().normalize());
      kafkaInputTopic = Objects.requireNonNull(kafkaInputTopic, "kafkaInputTopic");
      outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory")
          .map(path -> path.toAbsolutePath().normalize());
      kafkaOutputTopic = Objects.requireNonNull(kafkaOutputTopic, "kafkaOutputTopic");
    }

    /**
     * Indicates whether Kafka should be used as the poster input source.
     *
     * @return {@code true} when Kafka input is configured
     * @since RADAR 0.1-doc
     *
     * <p><strong>Concurrency:</strong> Thread-safe; relies on immutable optionals.</p>
     * <p><strong>Performance:</strong> Constant-time presence check.</p>
     * <p><strong>Observability:</strong> Gate metrics/log lines that differentiate Kafka versus file sources.</p>
     */
    public boolean hasKafkaInput() {
      return kafkaInputTopic.isPresent();
    }

    /**
     * Indicates whether Kafka should be used as the poster output sink.
     *
     * @return {@code true} when Kafka output is configured
     * @since RADAR 0.1-doc
     *
     * <p><strong>Concurrency:</strong> Thread-safe; immutable optionals.</p>
     * <p><strong>Performance:</strong> Constant-time presence check.</p>
     * <p><strong>Observability:</strong> Enables logging which pipelines stream posters to Kafka.</p>
     */
    public boolean hasKafkaOutput() {
      return kafkaOutputTopic.isPresent();
    }

    /**
     * Returns the file-system input directory if configured.
     *
     * @return optional directory containing reconstructed pairs
     * @since RADAR 0.1-doc
     *
     * <p><strong>Concurrency:</strong> Immutable optional view.</p>
     * <p><strong>Performance:</strong> Constant-time getter.</p>
     * <p><strong>Observability:</strong> Sanitized path may be logged at debug to confirm wiring.</p>
     */
    public Optional<Path> inputDirectory() {
      return inputDirectory;
    }

    /**
     * Returns the Kafka input topic if configured.
     *
     * @return optional Kafka topic delivering reconstructed pairs
     * @since RADAR 0.1-doc
     *
     * <p><strong>Concurrency:</strong> Immutable optional view.</p>
     * <p><strong>Performance:</strong> Constant-time getter.</p>
     * <p><strong>Observability:</strong> Use topic name in debug logs; do not log credentials.</p>
     */
    public Optional<String> kafkaInputTopic() {
      return kafkaInputTopic;
    }

    /**
     * Returns the file-system output directory if configured.
     *
     * @return optional directory where poster artifacts should be written
     * @since RADAR 0.1-doc
     *
     * <p><strong>Concurrency:</strong> Immutable optional view.</p>
     * <p><strong>Performance:</strong> Constant-time getter.</p>
     * <p><strong>Observability:</strong> Validate directory existence when logging startup configuration.</p>
     */
    public Optional<Path> outputDirectory() {
      return outputDirectory;
    }

    /**
     * Returns the Kafka output topic if configured.
     *
     * @return optional Kafka topic receiving rendered posters
     * @since RADAR 0.1-doc
     *
     * <p><strong>Concurrency:</strong> Immutable optional view.</p>
     * <p><strong>Performance:</strong> Constant-time getter.</p>
     * <p><strong>Observability:</strong> Include sanitized topic name when logging poster wiring.</p>
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
     *
     * <p><strong>Concurrency:</strong> Thread-safe; the underlying optional is immutable.</p>
     * <p><strong>Performance:</strong> Constant-time optional unwrap.</p>
     * <p><strong>Observability:</strong> Exception message flags missing file input for operator remediation.</p>
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
     *
     * <p><strong>Concurrency:</strong> Thread-safe optional unwrap.</p>
     * <p><strong>Performance:</strong> Constant-time.</p>
     * <p><strong>Observability:</strong> Exception message includes that Kafka input is missing for this protocol.</p>
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
     *
     * <p><strong>Concurrency:</strong> Thread-safe optional unwrap.</p>
     * <p><strong>Performance:</strong> Constant-time.</p>
     * <p><strong>Observability:</strong> Exception message highlights missing file output configuration.</p>
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
     *
     * <p><strong>Concurrency:</strong> Thread-safe optional unwrap.</p>
     * <p><strong>Performance:</strong> Constant-time.</p>
     * <p><strong>Observability:</strong> Exception message highlights missing Kafka output wiring.</p>
     */
    public String requireKafkaOutputTopic() {
      return kafkaOutputTopic.orElseThrow(() -> new IllegalStateException("kafka output not configured"));
    }
  }

  /**
   * <strong>What:</strong> Controls how aggressively poster pipelines decode transfer/content encodings.
   * <p><strong>Why:</strong> Allows operators to balance fidelity against processing cost when rendering reports.</p>
   * <p><strong>Role:</strong> Enum consumed by poster pipelines to drive decoding behaviour.</p>
   * <p><strong>Thread-safety:</strong> Enum constants are immutable.</p>
   * <p><strong>Performance:</strong> Constant-time checks.</p>
   * <p><strong>Observability:</strong> Selected mode should be logged for supportability.</p>
   *
   * @since 0.1.0
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
     *
     * <p><strong>Concurrency:</strong> Thread-safe static helper.</p>
     * <p><strong>Performance:</strong> Normalizes input to lower-case and matches via switch; O(n) in input length.</p>
     * <p><strong>Observability:</strong> Callers should surface invalid values to the operator.</p>
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
     * Indicates whether transfer encodings (e.g., chunked) should be decoded.
     *
     * @return {@code true} when transfer encoding should be removed
     * @since RADAR 0.1-doc
     *
     * <p><strong>Concurrency:</strong> Thread-safe enum check.</p>
     * <p><strong>Performance:</strong> Constant-time comparison.</p>
     * <p><strong>Observability:</strong> Helps poster pipelines log decode decisions.</p>
     */
    public boolean decodeTransferEncoding() {
      return this == TRANSFER || this == ALL;
    }

    /**
     * Indicates whether content encodings (e.g., gzip) should be decoded.
     *
     * @return {@code true} when payloads should be fully decoded
     * @since RADAR 0.1-doc
     *
     * <p><strong>Concurrency:</strong> Thread-safe enum check.</p>
     * <p><strong>Performance:</strong> Constant-time comparison.</p>
     * <p><strong>Observability:</strong> Useful when logging decode depth.</p>
     */
    public boolean decodeContentEncoding() {
      return this == ALL;
    }
  }
}
