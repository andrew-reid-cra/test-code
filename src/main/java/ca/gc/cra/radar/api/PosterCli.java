package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.PosterUseCase;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.config.PosterConfig;
import java.util.Map;

/**
 * Entry point for rendering reconstructed protocol traffic into poster outputs.
 * <p>Supports file and Kafka IO modes for both inputs and outputs.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class PosterCli {
  private PosterCli() {}

  /**
   * Parses poster CLI arguments and triggers the {@link PosterUseCase} pipeline.
   *
   * @param args CLI arguments in {@code key=value} form
   * @throws Exception if configuration validation or poster processing fails
   * @since RADAR 0.1-doc
   */
  public static void main(String[] args) throws Exception {
    Map<String, String> kv = CliArgsParser.toMap(args);
    PosterConfig config;
    try {
      config = PosterConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      System.err.println("poster: " + ex.getMessage());
      usage();
      return;
    }

    if ((config.ioMode() == IoMode.KAFKA || config.posterOutMode() == IoMode.KAFKA)
        && config.kafkaBootstrap().isEmpty()) {
      System.err.println("poster: kafkaBootstrap is required when Kafka input/output selected");
      usage();
      return;
    }

    new PosterUseCase().run(config);
  }

  private static void usage() {
    System.err.println(
        "usage: poster [httpIn=<dir>|httpIn=kafka:<topic>] [tnIn=<dir>|tnIn=kafka:<topic>] "
            + "[httpOut=<dir>|posterOutMode=KAFKA kafkaHttpReportsTopic=<topic>] "
            + "[tnOut=<dir>|posterOutMode=KAFKA kafkaTnReportsTopic=<topic>] "
            + "[ioMode=FILE|KAFKA] [posterOutMode=FILE|KAFKA] [kafkaBootstrap=host:port] "
            + "[kafkaHttpPairsTopic=..] [kafkaTnPairsTopic=..] [decode=none|transfer|all] -- renders message pairs");
  }
}
