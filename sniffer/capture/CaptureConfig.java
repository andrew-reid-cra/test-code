package sniffer.capture;

import java.nio.file.Path;

final class CaptureConfig {
  final String iface;
  final String bpf;
  final int snap;
  final int bufBytes;
  final int timeoutMs;
  final boolean promisc;
  final boolean immediate;
  final Path outDir;
  final String fileBase;
  final int rollMiB;
  final Path httpOutDir;
  final Path tnOutDir;

  static CaptureConfig fromArgs(String[] args) {
    ca.gc.cra.radar.config.CaptureConfig cfg = ca.gc.cra.radar.config.CaptureConfig.fromArgs(args);
    return new CaptureConfig(
        cfg.iface(),
        cfg.filter(),
        cfg.snaplen(),
        cfg.bufferBytes(),
        cfg.timeoutMillis(),
        cfg.promiscuous(),
        cfg.immediate(),
        cfg.outputDirectory(),
        cfg.fileBase(),
        cfg.rollMiB(),
        cfg.httpOutputDirectory(),
        cfg.tn3270OutputDirectory());
  }

  private CaptureConfig(
      String iface,
      String bpf,
      int snap,
      int bufBytes,
      int timeoutMs,
      boolean promisc,
      boolean immediate,
      Path outDir,
      String fileBase,
      int rollMiB,
      Path httpOutDir,
      Path tnOutDir) {
    this.iface = iface;
    this.bpf = bpf;
    this.snap = snap;
    this.bufBytes = bufBytes;
    this.timeoutMs = timeoutMs;
    this.promisc = promisc;
    this.immediate = immediate;
    this.outDir = outDir;
    this.fileBase = fileBase;
    this.rollMiB = rollMiB;
    this.httpOutDir = httpOutDir;
    this.tnOutDir = tnOutDir;
  }
}
