package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.PosterUseCase;
import ca.gc.cra.radar.config.IoMode;
import ca.gc.cra.radar.config.PosterConfig;
import java.util.Map;

public final class PosterCli {
  private PosterCli() {}

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
