package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.config.AssembleConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.io.IOException;
import java.util.Map;
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
      "usage: assemble in=./cap-out|in=kafka:<topic> out=./pairs-out [httpOut=<dir>] [tnOut=<dir>] "
          + "[httpEnabled=true] [tnEnabled=false] [ioMode=FILE|KAFKA] "
          + "[kafkaBootstrap=host:port] [kafkaSegmentsTopic=radar.segments] "
          + "[kafkaHttpPairsTopic=radar.http.pairs] [kafkaTnPairsTopic=radar.tn3270.pairs]";
  private static final String HELP_TEXT = """
      RADAR assemble pipeline

      Usage:
        assemble in=./cap-out out=./pairs-out [options]

      Required options:
        in=PATH|in=kafka:TOPIC   Segment input directory or Kafka topic
        out=PATH                 Output directory for paired messages when ioMode=FILE

      Common options:
        httpOut=PATH             Optional override for HTTP pair output directory
        tnOut=PATH               Optional override for TN3270 pair output directory
        ioMode=FILE|KAFKA        Selects file or Kafka input for segments
        kafkaBootstrap=HOST:PORT Required when ioMode=KAFKA or posterOutMode=KAFKA
        --help                   Show detailed help
        --verbose                Enable DEBUG logging for troubleshooting

      Examples:
        assemble in=./cap-out out=./pairs-out httpEnabled=true tnEnabled=false
        assemble in=kafka:radar.segments out=./pairs-out ioMode=KAFKA kafkaBootstrap=localhost:9092
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

    Map<String, String> kv = CliArgsParser.toMap(input.keyValueArgs());
    AssembleConfig config;
    try {
      config = AssembleConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid assemble arguments: {}", ex.getMessage());
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    if (config.ioMode() == IoMode.KAFKA && config.kafkaBootstrap().isEmpty()) {
      log.error("Invalid assemble arguments: kafkaBootstrap is required when ioMode=KAFKA");
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.INVALID_ARGS;
    }

    CompositionRoot root = new CompositionRoot(ca.gc.cra.radar.config.Config.defaults());
    AssembleUseCase useCase = root.assembleUseCase(config);

    try {
      useCase.run();
      log.info("Assemble pipeline completed for input {}", config.inputDirectory());
      return ExitCode.SUCCESS;
    } catch (IllegalArgumentException ex) {
      log.error("Assemble configuration error: {}", ex.getMessage(), ex);
      return ExitCode.CONFIG_ERROR;
    } catch (IOException ex) {
      log.error("Assemble pipeline I/O failure while processing {}", config.inputDirectory(), ex);
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
}
