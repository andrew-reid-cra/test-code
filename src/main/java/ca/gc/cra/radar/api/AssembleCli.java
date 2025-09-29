package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.config.AssembleConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.config.ConfigMerger;
import ca.gc.cra.radar.config.DefaultsForMode;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.config.YamlConfigLoader;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import ca.gc.cra.radar.validation.Paths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for running the assemble pipeline over captured segments.
 *
 * @since RADAR 0.1-doc
 */
public final class AssembleCli {
  private static final Logger log = LoggerFactory.getLogger(AssembleCli.class);
  private static final String SUMMARY_USAGE =
      "usage: assemble in=PATH|in=kafka:TOPIC out=PATH [ioMode=FILE|KAFKA] "
          + "[httpOut=PATH] [tnOut=PATH] [httpEnabled=true|false] [tnEnabled=true|false] "
          + "[kafkaBootstrap=HOST:PORT] [--dry-run] [--allow-overwrite] "
          + "[metricsExporter=otlp|none] [otelEndpoint=URL] [otelResourceAttributes=K=V,...]";
  private static final String HELP_TEXT = """
      RADAR assemble pipeline

      Usage:
        assemble in=./cap-out out=./pairs-out [options]

      Required:
        in=PATH|in=kafka:TOPIC   Segment input directory or Kafka topic
        out=PATH                 Output directory root when writing files

      Optional (validated):
        httpOut=PATH             Override HTTP output directory
        tnOut=PATH               Override TN3270 output directory
        ioMode=FILE|KAFKA        FILE reads from directories; KAFKA requires kafkaBootstrap
        kafkaBootstrap=HOST:PORT Validated host/port when Kafka input/output configured
        kafkaSegmentsTopic=TOPIC Sanitized topic supplying segments
        kafkaHttpPairsTopic=TOPIC Sanitized topic for HTTP pairs
        kafkaTnPairsTopic=TOPIC  Sanitized topic for TN3270 pairs
        httpEnabled=true|false   Enable HTTP reconstruction (default true)
        tnEnabled=true|false     Enable TN3270 reconstruction (default false)
        --dry-run                Validate inputs and print plan without assembling
        --allow-overwrite        Permit writing into non-empty output directories
        metricsExporter=otlp|none  Configure metrics exporter (default otlp)
        otelEndpoint=URL           OTLP metrics endpoint when exporter=otlp
        otelResourceAttributes=K=V Comma-separated OTel resource attributes
        --verbose                Enable DEBUG logging
        --help                   Show this message

      Notes:
        ? Paths are canonicalized; directories must exist (for inputs) or be empty unless --allow-overwrite.
        ? Kafka topics are constrained to [A-Za-z0-9._-].
      """;

  private AssembleCli() {}

  /**
   * Entry point invoked by the JVM.
   *
   * @param args raw CLI arguments
   */
  public static void main(String[] args) {
    ExitCode exit = run(args);
    System.exit(exit.code());
  }

  /**
   * Executes the assemble CLI logic using structured logging and exit codes.
   *
   * @param args raw CLI arguments
   * @return exit code capturing the outcome
   */
  static ExitCode run(String[] args) {
    CliInput input = CliInput.parse(args);
    if (input.help()) {
      CliPrinter.println(HELP_TEXT.stripTrailing());
      return ExitCode.SUCCESS;
    }
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for assemble CLI");
    }

    boolean dryRunFlag = input.hasFlag("--dry-run");
    boolean allowOverwriteFlag = input.hasFlag("--allow-overwrite");

    Map<String, String> kv;
    try {
      kv = new LinkedHashMap<>(CliArgsParser.toMap(input.keyValueArgs()));
    } catch (IllegalArgumentException ex) {
      log.error("Invalid argument: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    String configPath = ConfigCliUtils.extractConfigPath(kv);

    Optional<Map<String, String>> yamlConfig = Optional.empty();
    if (configPath != null) {
      Path yamlPath = Path.of(configPath);
      if (!java.nio.file.Files.exists(yamlPath)) {
        log.error("Configuration file does not exist: {}", yamlPath);
        CliPrinter.println(SUMMARY_USAGE);
        return ExitCode.INVALID_ARGS;
      }
      try {
        yamlConfig = YamlConfigLoader.load(yamlPath, "assemble");
      } catch (IllegalArgumentException ex) {
        log.error("Invalid YAML configuration: {}", ex.getMessage());
        CliPrinter.println(SUMMARY_USAGE);
        return ExitCode.INVALID_ARGS;
      } catch (IOException ex) {
        log.error("Unable to read configuration file {}", yamlPath, ex);
        return ExitCode.IO_ERROR;
      }
    }

    Map<String, String> defaults = DefaultsForMode.asFlatMap("assemble");
    Map<String, String> effective;
    try {
      effective = ConfigMerger.buildEffectiveConfig("assemble", yamlConfig, kv, defaults, log::warn);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid assemble arguments: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    boolean dryRun = dryRunFlag || ConfigCliUtils.parseBoolean(effective, "dryRun");
    boolean allowOverwrite = allowOverwriteFlag || ConfigCliUtils.parseBoolean(effective, "allowOverwrite");

    Map<String, String> configInputs = new LinkedHashMap<>(effective);
    String metricsExporter = effective.getOrDefault("metricsExporter", "otlp");
    TelemetryConfigurator.configureMetrics(configInputs);

    AssembleConfig config;
    try {
      config = AssembleConfig.fromMap(configInputs);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid assemble arguments: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    ValidatedPaths validated;
    try {
      validated = validatePaths(config, allowOverwrite, !dryRun);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid assemble path configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    if (dryRun) {
      printDryRunPlan(config, validated, allowOverwrite);
      return ExitCode.SUCCESS;
    }

    CompositionRoot root = new CompositionRoot(ca.gc.cra.radar.config.Config.defaults());
    AssembleUseCase useCase = root.assembleUseCase(config);

    try {
      log.info(
          "Configured assemble pipeline: ioMode={}, input={}, output={}, metricsExporter={}",
          config.ioMode(),
          config.ioMode() == IoMode.KAFKA ? config.kafkaSegmentsTopic() : config.inputDirectory(),
          config.ioMode() == IoMode.KAFKA
              ? config.kafkaHttpPairsTopic() + "," + config.kafkaTnPairsTopic()
              : config.outputDirectory(),
          metricsExporter);
      useCase.run();
      log.info("Assemble pipeline completed for input {}", validated.input());
      return ExitCode.SUCCESS;
    } catch (IllegalArgumentException ex) {
      log.error("Assemble configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (IOException ex) {
      log.error("Assemble pipeline I/O failure while processing {}", validated.input(), ex);
      return ExitCode.IO_ERROR;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Assemble pipeline interrupted; shutting down", ex);
      return ExitCode.INTERRUPTED;
    } catch (RuntimeException ex) {
      log.error("Unexpected runtime failure in assemble pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    } catch (Exception ex) {
      log.error("Unexpected checked exception in assemble pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    }
  }

  private static ValidatedPaths validatePaths(
      AssembleConfig config, boolean allowOverwrite, boolean createIfMissing) {
    Path input = config.inputDirectory();
    if (config.ioMode() == IoMode.FILE) {
      input = ensureReadableDirectory(input);
    }

    Path outputRoot = Paths.validateWritableDir(config.outputDirectory(), null, createIfMissing, allowOverwrite);
    Optional<Path> http = config.httpOutputDirectory()
        .map(path -> Paths.validateWritableDir(path, null, createIfMissing, allowOverwrite))
        .or(() -> Optional.of(Paths.validateWritableDir(
            config.outputDirectory().resolve("http"), null, createIfMissing, allowOverwrite)));
    Optional<Path> tn = config.tnOutputDirectory()
        .map(path -> Paths.validateWritableDir(path, null, createIfMissing, allowOverwrite))
        .or(() -> Optional.of(Paths.validateWritableDir(
            config.outputDirectory().resolve("tn3270"), null, createIfMissing, allowOverwrite)));

    return new ValidatedPaths(input, outputRoot, http.orElse(null), tn.orElse(null));
  }

  private static Path ensureReadableDirectory(Path path) {
    try {
      Path real = path.toRealPath();
      if (!Files.isDirectory(real)) {
        throw new IllegalArgumentException("Input directory must exist: " + path);
      }
      if (!Files.isReadable(real)) {
        throw new IllegalArgumentException("Input directory is not readable: " + path);
      }
      return real;
    } catch (IOException ex) {
      throw new IllegalArgumentException("Unable to access input directory: " + path, ex);
    }
  }

  private static void printDryRunPlan(
      AssembleConfig config, ValidatedPaths paths, boolean allowOverwrite) {
    CliPrinter.printLines(
        "Assemble dry-run: no files will be produced.",
        " Input mode        : " + config.ioMode(),
        " Input directory   : " + (config.ioMode() == IoMode.FILE ? paths.input() : "<Kafka>"),
        " Output root       : " + paths.outputRoot(),
        " HTTP enabled      : " + config.httpEnabled(),
        " TN3270 enabled    : " + config.tnEnabled(),
        " HTTP out dir      : " + (paths.http() == null ? "<derived>" : paths.http()),
        " TN3270 out dir    : " + (paths.tn() == null ? "<derived>" : paths.tn()),
        " Kafka bootstrap   : " + config.kafkaBootstrap().orElse("<none>"),
        " Kafka segments    : " + config.kafkaSegmentsTopic(),
        " Kafka HTTP pairs  : " + config.kafkaHttpPairsTopic(),
        " Kafka TN pairs    : " + config.kafkaTnPairsTopic(),
        " Allow overwrite   : " + allowOverwrite,
        " Re-run without --dry-run to assemble segments.");
  }

  private record ValidatedPaths(Path input, Path outputRoot, Path http, Path tn) {}
}

