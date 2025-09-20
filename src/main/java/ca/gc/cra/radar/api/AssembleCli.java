package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.config.AssembleConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import ca.gc.cra.radar.config.IoMode;
import java.util.Map;

public final class AssembleCli {
  private AssembleCli() {}

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
