package ca.gc.cra.radar.api;

import ca.gc.cra.radar.application.pipeline.SegmentCaptureUseCase;
import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import java.util.Map;

/**
 * Capture CLI backed by the new port-based pipeline.
 */
public final class CaptureCli {
  private CaptureCli() {}

  public static void main(String[] args) throws Exception {
    Map<String, String> kv = CliArgsParser.toMap(args);
    if (!kv.containsKey("iface") || kv.get("iface").isBlank()) {
      usage();
      return;
    }

    CaptureConfig captureCfg;
    try {
      captureCfg = CaptureConfig.fromMap(kv);
    } catch (IllegalArgumentException ex) {
      System.err.println("Invalid capture options: " + ex.getMessage());
      usage();
      return;
    }

    CompositionRoot root =
        new CompositionRoot(ca.gc.cra.radar.config.Config.defaults(), captureCfg);
    SegmentCaptureUseCase useCase = root.segmentCaptureUseCase();
    useCase.run();
  }

  private static void usage() {
    System.err.println(
        "usage: iface=<nic> [bufmb=1024 snap=65535 timeout=1 bpf='<expr>' immediate=true promisc=true out=out/segments fileBase=segments rollMiB=1024]");
  }
}
