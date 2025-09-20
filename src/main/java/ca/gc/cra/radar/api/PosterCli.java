package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.PosterUseCase;
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

    new PosterUseCase().run(config);
  }

  private static void usage() {
    System.err.println(
        "usage: poster in=<dir> out=<dir> [decode=none|transfer|all] -- renders message pairs");
  }
}
