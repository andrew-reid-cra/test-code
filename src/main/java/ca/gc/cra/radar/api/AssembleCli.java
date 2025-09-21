package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.config.AssembleConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.config.IoMode;
import java.util.Map;

/**
 * Entry point for running the assemble pipeline over captured segments.
 * <p>Utility holder for {@link #main(String[])}; not intended for concurrent use.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class AssembleCli {
  private AssembleCli() {}

  /**
   * Parses assemble CLI arguments and executes the configured pipeline.
   *
   * @param args CLI arguments in {@code key=value} form; requires {@code in} and {@code out} targets
   * @throws Exception if configuration validation or assemble processing fails
   * @since RADAR 0.1-doc
   */
  public static void main(String[] args) throws Exception {
    Map<String, String> kv = CliArgsParser.toMap(args);
    AssembleConfig config;
    try {
      config = AssembleConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      System.err.println("assemble: " + ex.getMessage());
      usage();
      return;
    }

    if (config.ioMode() == IoMode.KAFKA && config.kafkaBootstrap().isEmpty()) {
      System.err.println("assemble: kafkaBootstrap is required for Kafka mode");
      usage();
      return;
    }

    CompositionRoot root = new CompositionRoot(ca.gc.cra.radar.config.Config.defaults());
    AssembleUseCase useCase = root.assembleUseCase(config);
    useCase.run();
  }

  private static void usage() {
    System.err.println(
        "usage: assemble in=./cap-out|in=kafka:<topic> out=./pairs-out [httpOut=<dir>] [tnOut=<dir>] "
            + "[httpEnabled=true] [tnEnabled=false] [ioMode=FILE|KAFKA] "
            + "[kafkaBootstrap=host:port] [kafkaSegmentsTopic=radar.segments] "
            + "[kafkaHttpPairsTopic=radar.http.pairs] [kafkaTnPairsTopic=radar.tn3270.pairs]");
  }
}
