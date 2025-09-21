package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.PosterUseCase;
import ca.gc.cra.radar.config.PosterConfig;
import ca.gc.cra.radar.logging.LoggingConfigurator;
import java.io.IOException;
import java.util.Map;
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
      "usage: poster [httpIn=<dir>|httpIn=kafka:<topic>] [tnIn=<dir>|tnIn=kafka:<topic>] "
          + "[httpOut=<dir>|posterOutMode=KAFKA kafkaHttpReportsTopic=<topic>] "
          + "[tnOut=<dir>|posterOutMode=KAFKA kafkaTnReportsTopic=<topic>] "
          + "[ioMode=FILE|KAFKA] [posterOutMode=FILE|KAFKA] [kafkaBootstrap=host:port] "
          + "[kafkaHttpPairsTopic=..] [kafkaTnPairsTopic=..] [decode=none|transfer|all]";
  private static final String HELP_TEXT = """
      RADAR poster pipeline

      Usage:
        poster [protocol options] [global options]

      Required options:
        Specify at least one input via httpIn=PATH|kafka:TOPIC or tnIn=PATH|kafka:TOPIC

      Common options:
        httpOut=PATH                 HTTP report output directory when posterOutMode=FILE
        tnOut=PATH                   TN3270 report output directory when posterOutMode=FILE
        posterOutMode=FILE|KAFKA     Selects file or Kafka outputs
        kafkaBootstrap=HOST:PORT     Required when Kafka inputs or outputs are configured
        decode=none|transfer|all     Controls poster decoding behavior
        --help                       Show detailed help
        --verbose                    Enable DEBUG logging for troubleshooting

      Examples:
        poster httpIn=./http-in httpOut=./http-out decode=all
        poster httpIn=kafka:radar.http.pairs posterOutMode=KAFKA kafkaHttpReportsTopic=radar.http.reports \
               kafkaBootstrap=localhost:9092
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

    Map<String, String> kv = CliArgsParser.toMap(input.keyValueArgs());
    PosterConfig config;
    try {
      config = PosterConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      log.error("Invalid poster configuration: {}", ex.getMessage(), ex);
      CliPrinter.println(SUMMARY_USAGE);
      return ExitCode.CONFIG_ERROR;
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
}
