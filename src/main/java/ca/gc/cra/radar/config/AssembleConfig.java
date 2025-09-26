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
 * <strong>What:</strong> Captures configuration for the assemble pipeline that stitches captured segments into protocol pairs.
 * <p><strong>Why:</strong> Consolidates CLI flags and environment defaults so assemble runs stay reproducible across environments.</p>
 * <p><strong>Role:</strong> Adapter configuration aggregate for the assemble stage (assemble CLI).</p>
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Describe segment ingestion/output IO modes (file or Kafka).</li>
 *   <li>Enable protocol-specific reconstruction knobs and directory overrides.</li>
 *   <li>Validate Kafka bootstrap requirements and ensure at least one protocol is enabled.</li>
 * </ul>
 * <p><strong>Thread-safety:</strong> Immutable record; safe for concurrent reads.</p>
 * <p><strong>Performance:</strong> Normalizes paths/topics during construction; accessors are constant-time.</p>
 * <p><strong>Observability:</strong> Feed into metrics/log attributes (e.g., {@code assemble.ioMode}).</p>
 *
 * @param ioMode IO strategy for reading captured segments ({@link IoMode#FILE} or {@link IoMode#KAFKA}); {@code null} defaults to {@link IoMode#FILE}
 * @param kafkaBootstrap optional Kafka bootstrap servers in {@code host:port[,host:port]} format when Kafka mode is active
 * @param kafkaSegmentsTopic Kafka topic supplying captured segments in Kafka mode
 * @param kafkaHttpPairsTopic Kafka topic receiving HTTP pairs in Kafka mode
 * @param kafkaTnPairsTopic Kafka topic receiving TN3270 pairs in Kafka mode
 * @param inputDirectory directory containing serialized segment files when in file mode
 * @param outputDirectory root directory for assembled outputs
 * @param httpEnabled whether HTTP reconstruction is enabled
 * @param tnEnabled whether TN3270 reconstruction is enabled
 * @param httpOutputDirectory optional override for the HTTP output directory
 * @param tnOutputDirectory optional override for the TN3270 output directory
 * @since 0.1.0
 * @implNote Enforces that at least one protocol is enabled and requires Kafka bootstrap servers whenever {@code ioMode} resolves to {@link IoMode#KAFKA}.
 * @see ca.gc.cra.radar.application.pipeline.AssembleUseCase
 * @see ca.gc.cra.radar.config.PosterConfig
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
   * Normalizes assemble configuration values and enforces invariants.
   *
   * @throws IllegalArgumentException if no protocol is enabled or Kafka bootstrap servers are missing for Kafka mode
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Executed during bootstrap on a single thread.</p>
   * <p><strong>Performance:</strong> Performs constant-time normalization and sanitization.</p>
   * <p><strong>Observability:</strong> Throws descriptive exceptions so CLI wrappers can report invalid inputs.</p>
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
   *
   * <p><strong>Concurrency:</strong> Thread-safe factory with no shared state.</p>
   * <p><strong>Performance:</strong> Allocates a single record instance with precomputed defaults.</p>
   * <p><strong>Observability:</strong> Defaults align with local dev paths under {@code ~/.radar/out}.</p>
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
   *
   * <p><strong>Concurrency:</strong> Call from a single thread; callers must not mutate {@code options} during parsing.</p>
   * <p><strong>Performance:</strong> Resolves paths and topics in O(n) relative to the keys provided.</p>
   * <p><strong>Observability:</strong> Validation exceptions mention the offending knob to aid CLI error messaging.</p>
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
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor on immutable state.</p>
   * <p><strong>Performance:</strong> Constant-time optional fallback.</p>
   * <p><strong>Observability:</strong> Include the resolved path in startup logs to confirm wiring.</p>
   */
  public Path effectiveHttpOut() {
    return httpOutputDirectory.orElseGet(() -> outputDirectory.resolve("http"));
  }

  /**
   * Resolves the effective TN3270 output directory, defaulting under {@link #outputDirectory}.
   *
   * @return directory for TN3270 output
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Thread-safe accessor.</p>
   * <p><strong>Performance:</strong> Constant-time optional fallback.</p>
   * <p><strong>Observability:</strong> Include in logs when TN3270 reconstruction is enabled.</p>
   */
  public Path effectiveTnOut() {
    return tnOutputDirectory.orElseGet(() -> outputDirectory.resolve("tn3270"));
  }

  /**
   * Parses boolean flags while honoring a default when the flag is missing.
   *
   * @param value raw string flag (e.g., "true", "false"); blanks fall back to {@code defaultValue}
   * @param defaultValue value returned when {@code value} is {@code null} or blank
   * @return parsed boolean
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure helper; safe for concurrent use.</p>
   * <p><strong>Performance:</strong> Constant-time trimming and parsing.</p>
   * <p><strong>Observability:</strong> Conservatively defaults to avoid surprising operators when flags are omitted.</p>
   */
  private static boolean parseBoolean(String value, boolean defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value.trim());
  }

  /**
   * Converts possibly blank strings supplied by operators into {@link Optional} values.
   *
   * @param value candidate string
   * @return optional containing the trimmed value when non-blank
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Stateless helper; safe for concurrent use.</p>
   * <p><strong>Performance:</strong> Constant-time trimming and length checks.</p>
   * <p><strong>Observability:</strong> Prevents whitespace-only flags from being mistaken for configured values.</p>
   */
  private static Optional<String> optionalString(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  /**
   * Validates and normalizes Kafka bootstrap settings.
   *
   * @param candidate optional bootstrap string
   * @return optional bootstrap string validated by {@link Net#validateHostPort(String)}
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Stateless helper.</p>
   * <p><strong>Performance:</strong> Delegates to validator; constant-time for typical host lists.</p>
   * <p><strong>Observability:</strong> Ensures invalid hosts are surfaced via exceptions.</p>
   */
  private static Optional<String> sanitizeBootstrap(Optional<String> candidate) {
    if (candidate == null) {
      return Optional.empty();
    }
    return candidate.map(Net::validateHostPort);
  }

  /**
   * Converts optional path arguments into normalized {@link Path} instances.
   *
   * @param name logical parameter name used for error reporting
   * @param value raw path string supplied by the operator
   * @return optional normalized path
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Stateless helper.</p>
   * <p><strong>Performance:</strong> Path parsing dominates; otherwise constant-time.</p>
   * <p><strong>Observability:</strong> Exceptions annotate the parameter name so logs remain actionable.</p>
   */
  private static Optional<Path> optionalPath(String name, String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(parsePath(name, value));
  }

  /**
   * Ensures optional paths are normalized and safe to use on disk.
   *
   * @param name logical parameter name used for diagnostics
   * @param candidate optional path supplied to the record constructor
   * @return optional normalized path
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Stateless helper.</p>
   * <p><strong>Performance:</strong> Constant-time normalization.</p>
   * <p><strong>Observability:</strong> Guards against null bytes so error messages remain clear.</p>
   */
  private static Optional<Path> sanitizeOptionalPath(String name, Optional<Path> candidate) {
    if (candidate == null || candidate.isEmpty()) {
      return Optional.empty();
    }
    return candidate.map(path -> normalizePath(name, path));
  }

  /**
   * Parses and normalizes filesystem paths from CLI input.
   *
   * @param name logical parameter name used for diagnostics
   * @param value raw path string supplied by operators
   * @return normalized absolute path
   * @throws IllegalArgumentException if the path is blank or invalid
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Stateless helper.</p>
   * <p><strong>Performance:</strong> Delegates to {@link Path#of(String, String...)}; cost proportional to path length.</p>
   * <p><strong>Observability:</strong> Exception text includes the offending value for CLI logs.</p>
   */
  private static Path parsePath(String name, String value) {
    try {
      return Path.of(Strings.requireNonBlank(name, value)).toAbsolutePath().normalize();
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException(name + " is not a valid path: " + value, ex);
    }
  }

  /**
   * Normalizes supplied paths while performing basic safety checks.
   *
   * @param name logical parameter name used for diagnostics
   * @param path path instance to normalize; must not be {@code null}
   * @return normalized absolute path
   * @throws IllegalArgumentException if the path contains null bytes
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Stateless helper.</p>
   * <p><strong>Performance:</strong> Constant-time normalization.</p>
   * <p><strong>Observability:</strong> Throws descriptive errors when invalid paths are encountered.</p>
   */
  private static Path normalizePath(String name, Path path) {
    Objects.requireNonNull(path, name + " must not be null");
    String raw = path.toString();
    if (raw.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must not contain null bytes");
    }
    return path.toAbsolutePath().normalize();
  }

  /**
   * Returns the first non-blank value among a set of CLI key aliases.
   *
   * @param map CLI arguments map
   * @param keys ordered list of aliases to examine
   * @return the first non-blank value or {@code null} when none present
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Pure helper.</p>
   * <p><strong>Performance:</strong> Linear scan over {@code keys}; each lookup constant-time.</p>
   * <p><strong>Observability:</strong> Leaves value untrimmed so downstream validation can produce precise errors.</p>
   */
  private static String firstNonBlank(Map<String, String> map, String... keys) {
    for (String key : keys) {
      String val = map.get(key);
      if (val != null && !val.isBlank()) {
        return val;
      }
    }
    return null;
  }

  /**
   * Resolves IO mode flags, defaulting when the flag is absent.
   *
   * @param value raw IO mode flag
   * @param fallback IO mode to use when {@code value} is {@code null} or blank
   * @return resolved IO mode
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Stateless helper.</p>
   * <p><strong>Performance:</strong> Constant-time.</p>
   * <p><strong>Observability:</strong> Delegates to {@link IoMode#fromString(String)} for validation.</p>
   */
  private static IoMode parseIoMode(String value, IoMode fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return IoMode.fromString(value.trim());
  }

  /**
   * Provides the default base directory under the user's home directory.
   *
   * @return base directory for assemble defaults
   * @since RADAR 0.1-doc
   *
   * <p><strong>Concurrency:</strong> Stateless helper.</p>
   * <p><strong>Performance:</strong> Constant-time path construction.</p>
   * <p><strong>Observability:</strong> Directory choice should be mentioned in CLI help ({@code ~/.radar/out}).</p>
   */
  private static Path defaultBaseDirectory() {
    String home = System.getProperty("user.home", ".");
    return Path.of(home, ".radar", "out");
  }
}
