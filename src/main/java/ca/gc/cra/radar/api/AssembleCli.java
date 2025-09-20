package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.AssembleUseCase;
import ca.gc.cra.radar.config.AssembleConfig;
import ca.gc.cra.radar.config.CompositionRoot;
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

    CompositionRoot root = new CompositionRoot(ca.gc.cra.radar.config.Config.defaults());
    AssembleUseCase useCase = root.assembleUseCase(config);
    useCase.run();
  }

  private static void usage() {
    System.err.println(
        "usage: in=./cap-out out=./pairs-out [httpEnabled=true] -- replays captured segments into message pairs");
  }
}
