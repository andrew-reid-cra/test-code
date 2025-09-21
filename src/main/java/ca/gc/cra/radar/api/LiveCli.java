package ca.gc.cra.radar.api;

import ca.gc.cra.radar.config.CaptureConfig;
import ca.gc.cra.radar.config.CompositionRoot;
import java.util.Map;

/**
 * Launches the live RADAR capture pipeline from CLI key-value arguments.
 * <p>Utility holder for the {@link #main(String[])} entry point; not thread-safe by design because it
 * performs process-level configuration.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class LiveCli {
  private LiveCli() {}

  /**
   * Parses command-line arguments and runs the live processing use case.
   *
   * @param args CLI arguments in {@code key=value} form; {@code iface} is required and must be non-blank
   * @throws Exception if configuration or live processing fails
   * @since RADAR 0.1-doc
   */
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
