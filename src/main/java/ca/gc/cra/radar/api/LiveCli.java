package ca.gc.cra.radar.api;

import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import java.util.Map;

public final class LiveCli {
  private LiveCli() {}

  public static void main(String[] args) throws Exception {
    Map<String, String> kv = CliArgsParser.toMap(args);

    if (!kv.containsKey("iface") || kv.get("iface").isBlank()) {
      usage();
      return;
    }

    CaptureConfig captureConfig;
    try {
      captureConfig = CaptureConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      usage();
      return;
    }

    CompositionRoot root =
        new CompositionRoot(ca.gc.cra.radar.config.Config.defaults(), captureConfig);
    root.liveProcessingUseCase().run();
  }

  private static void usage() {
    System.err.println(
        "usage: iface=<nic> [bufmb=1024 snap=65535 timeout=1 bpf='<expr>' immediate=true promisc=true]");
  }
}
