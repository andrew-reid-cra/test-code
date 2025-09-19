package sniffer.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

final class CaptureConfig {
  private static final Path DEFAULT_CONFIG_PATH = Paths.get("sniffer", "capture", "capture.properties");

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
  final Path tnOutDir;

  static CaptureConfig fromArgs(String[] args) {
    Map<String,String> kv = new HashMap<>();
    for (String a: args){ int i=a.indexOf('='); if (i>0) kv.put(a.substring(0,i), a.substring(i+1)); }

    Properties defaults = loadDefaults(kv.get("config"));

    String iface = firstNonNull(kv.get("iface"), defaults.getProperty("iface"), "eth0");
    String bpf   = firstNonNull(kv.get("bpf"), defaults.getProperty("bpf"), "");
    String snapStr = firstNonNull(kv.get("snap"), defaults.getProperty("snap"), "65535");
    String bufStr  = firstNonNull(kv.get("bufmb"), defaults.getProperty("bufmb"), "256");
    String timeoutStr = firstNonNull(kv.get("timeout"), defaults.getProperty("timeout"), "1");
    String promStr = firstNonNull(kv.get("promisc"), defaults.getProperty("promisc"), "true");
    String immStr  = firstNonNull(kv.get("immediate"), defaults.getProperty("immediate"), "true");
    String out     = firstNonNull(kv.get("out"), defaults.getProperty("out"), "./cap-out");
    String base    = firstNonNull(kv.get("base"), defaults.getProperty("base"), "cap");
    String rollStr = firstNonNull(kv.get("rollMiB"), defaults.getProperty("rollMiB"), "512");
    String tnOutRaw = firstNonNull(kv.get("tnOut"), defaults.getProperty("tnOut"), null);
    String tnEnabledStr = firstNonNull(kv.get("tnEnabled"), defaults.getProperty("tnEnabled"), null);

    int snap     = parseInt(snapStr, 65535);
    int bufmb    = parseInt(bufStr, 256);
    int timeout  = parseInt(timeoutStr, 1);
    boolean prom = parseBoolean(promStr, true);
    boolean imm  = parseBoolean(immStr, true);
    int roll     = parseInt(rollStr, 512);
    boolean tnEnabled = parseBoolean(tnEnabledStr, tnOutRaw != null && !tnOutRaw.isBlank());
    Path tnPath = (tnEnabled && tnOutRaw != null && !tnOutRaw.isBlank()) ? Paths.get(tnOutRaw) : null;

    return new CaptureConfig(iface,bpf,snap,bufmb*1024*1024,timeout,prom,imm,
        Paths.get(out), base, roll, tnPath);
  }

  private CaptureConfig(String iface, String bpf, int snap, int bufBytes, int timeoutMs,
                        boolean promisc, boolean immediate, Path outDir, String base, int rollMiB,
                        Path tnOutDir) {
    this.iface=iface; this.bpf=bpf; this.snap=snap; this.bufBytes=bufBytes;
    this.timeoutMs=timeoutMs; this.promisc=promisc; this.immediate=immediate;
    this.outDir=outDir; this.fileBase=base; this.rollMiB=rollMiB;
    this.tnOutDir=tnOutDir;
  }

  private static int parseInt(String s, int def){ try { return Integer.parseInt(s); } catch (Exception e){ return def; } }

  private static boolean parseBoolean(String s, boolean def) {
    if (s == null) return def;
    if ("true".equalsIgnoreCase(s)) return true;
    if ("false".equalsIgnoreCase(s)) return false;
    return def;
  }

  private static String firstNonNull(String... values) {
    for (String v : values) {
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  private static Properties loadDefaults(String configPath) {
    Properties props = new Properties();
    Path path = DEFAULT_CONFIG_PATH;

    if (configPath != null) {
      String trimmed = configPath.trim();
      if (!trimmed.isEmpty()) {
        try {
          path = Paths.get(trimmed);
        } catch (InvalidPathException e) {
          throw new IllegalArgumentException("Invalid capture config path: " + configPath, e);
        }
      }
    }

    if (path != null && Files.exists(path)) {
      try (InputStream in = Files.newInputStream(path)) {
        props.load(in);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to load capture config from " + path, e);
      }
    }

    return props;
  }
}
