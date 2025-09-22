package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.PosterUseCase;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.config.PosterConfig.ProtocolConfig;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import ca.gc.cra.radar.validation.Paths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for rendering reconstructed protocol traffic into poster outputs.
 *
 * @since RADAR 0.1-doc
 */
public final class PosterCli {
  private static final Logger log = LoggerFactory.getLogger(PosterCli.class);
  private static final String SUMMARY_USAGE =
      "usage: poster [httpIn=PATH|kafka:TOPIC] [tnIn=PATH|kafka:TOPIC] "
          + "[httpOut=PATH] [tnOut=PATH] [ioMode=FILE|KAFKA] [posterOutMode=FILE|KAFKA] "
          + "[kafkaBootstrap=HOST:PORT] [decode=none|transfer|all] [--dry-run] [--allow-overwrite]";
  private static final String HELP_TEXT = """
      RADAR poster pipeline

      Usage:
        poster [protocol options] [global options]

      Inputs (at least one required):
        httpIn=PATH|kafka:TOPIC   HTTP pair input directory or Kafka topic ([A-Za-z0-9._-])
        tnIn=PATH|kafka:TOPIC     TN3270 pair input directory or Kafka topic

      Outputs:
        httpOut=PATH              HTTP report directory when posterOutMode=FILE
        tnOut=PATH                TN3270 report directory when posterOutMode=FILE
        posterOutMode=FILE|KAFKA  Default FILE writes under ~/.radar/out; KAFKA requires kafkaBootstrap

      Global options:
        ioMode=FILE|KAFKA         FILE reads directories; KAFKA requires kafkaBootstrap
        kafkaBootstrap=HOST:PORT  Validated host/port for Kafka inputs/outputs
        decode=none|transfer|all  Poster decoding behaviour (default none)
        --dry-run                 Validate inputs and print plan without rendering
        --allow-overwrite         Permit writing into non-empty output directories
        --verbose                 Enable DEBUG logging
        --help                    Show this message

      Notes:
        ? File inputs must exist and be readable. File outputs are created as needed unless --dry-run.
        ? Directories are canonicalized; reuse requires --allow-overwrite.
        ? Kafka topics are sanitized to [A-Za-z0-9._-].
      """;

  private PosterCli() {}

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
   * Executes the poster CLI and returns a normalized exit code.
   *
   * @param args raw CLI arguments
   * @return exit code describing the outcome
   */
  static ExitCode run(String[] args) {
    CliInput input = CliInput.parse(args);
    if (input.help()) {
      CliPrinter.println(HELP_TEXT.stripTrailing());
      return ExitCode.SUCCESS;
    }
    if (input.verbose()) {
      LoggingConfigurator.enableVerboseLogging();
      log.debug("Verbose logging enabled for poster CLI");
    }

    boolean dryRun = input.hasFlag("--dry-run");
    boolean allowOverwrite = input.hasFlag("--allow-overwrite");

    Map<String, String> kv;
    try {
      kv = CliArgsParser.toMap(input.keyValueArgs());
    } catch (IllegalArgumentException ex) {
      log.error("Invalid argument: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    PosterConfig config;
    try {
      config = PosterConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid poster configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    ValidatedPosterPaths validated;
    try {
      validated = validateProtocols(config, allowOverwrite, !dryRun);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid file path configuration: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    if (dryRun) {
      printDryRunPlan(config, validated, allowOverwrite);
      return ExitCode.SUCCESS;
    }

    PosterUseCase useCase = new PosterUseCase();
    try {
      log.info("Starting poster pipelines with decode mode {}", config.decodeMode());
      useCase.run(config);
      log.info("Poster pipelines completed successfully");
      return ExitCode.SUCCESS;
    } catch (IOException ex) {
      log.error("Poster pipeline I/O failure", ex);
      return ExitCode.IO_ERROR;
    } catch (IllegalArgumentException ex) {
      log.error("Poster pipeline configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("Poster pipeline interrupted; shutting down", ex);
      return ExitCode.INTERRUPTED;
    } catch (RuntimeException ex) {
      log.error("Unexpected runtime failure in poster pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    } catch (Exception ex) {
      log.error("Unexpected checked exception in poster pipeline", ex);
      return ExitCode.RUNTIME_FAILURE;
    }
  }

  private static ValidatedPosterPaths validateProtocols(
      PosterConfig config, boolean allowOverwrite, boolean createIfMissing) {
    ProtocolValidation http = config.http().map(cfg ->
        validateProtocol("HTTP", cfg, config.posterOutMode(), allowOverwrite, createIfMissing))
        .orElse(null);
    ProtocolValidation tn = config.tn3270().map(cfg ->
        validateProtocol("TN3270", cfg, config.posterOutMode(), allowOverwrite, createIfMissing))
        .orElse(null);
    return new ValidatedPosterPaths(http, tn);
  }

  private static ProtocolValidation validateProtocol(
      String name,
      ProtocolConfig cfg,
      IoMode posterOutMode,
      boolean allowOverwrite,
      boolean createIfMissing) {
    Path inputDir = cfg.inputDirectory()
        .map(PosterCli::ensureReadableDirectory)
        .orElse(null);
    Path outputDir = cfg.outputDirectory()
        .map(path -> Paths.validateWritableDir(path, null, createIfMissing, allowOverwrite))
        .orElse(null);
    if (posterOutMode == IoMode.FILE) {
      if (outputDir == null) {
        throw new IllegalArgumentException(name + " output directory required for FILE posterOutMode");
      }
    }
    return new ProtocolValidation(name, inputDir, outputDir, cfg.kafkaInputTopic(), cfg.kafkaOutputTopic());
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
      PosterConfig config, ValidatedPosterPaths paths, boolean allowOverwrite) {
    CliPrinter.printLines(
        "Poster dry-run: no reports will be produced.",
        " Input mode       : " + config.ioMode(),
        " Poster out mode  : " + config.posterOutMode(),
        " Kafka bootstrap  : " + config.kafkaBootstrap().orElse("<none>"),
        " Decode mode      : " + config.decodeMode(),
        describeProtocol(paths.http(), config.posterOutMode()),
        describeProtocol(paths.tn3270(), config.posterOutMode()),
        " Allow overwrite  : " + allowOverwrite,
        " Re-run without --dry-run to render posters.");
  }

  private static String describeProtocol(ProtocolValidation validation, IoMode posterOutMode) {
    if (validation == null) {
      return " Protocol disabled";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(" ").append(validation.name()).append(" input    : ");
    sb.append(validation.fileInput().map(Object::toString).orElse("<Kafka>"));
    sb.append(" | output: ");
    if (posterOutMode == IoMode.FILE) {
      sb.append(
          validation.fileOutput().map(Object::toString).orElse("<required output missing>"));
    } else {
      sb.append(validation.kafkaOutput().orElse("<Kafka>"));
    }
    return sb.toString();
  }

  private record ProtocolValidation(
      String name,
      Path fileInput,
      Path fileOutput,
      Optional<String> kafkaInput,
      Optional<String> kafkaOutput) {}

  private record ValidatedPosterPaths(ProtocolValidation http, ProtocolValidation tn3270) {}
}


