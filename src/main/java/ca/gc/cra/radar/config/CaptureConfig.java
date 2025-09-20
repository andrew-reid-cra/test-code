package ca.gc.cra.radar.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public record CaptureConfig(
    String iface,
    String filter,
    int snaplen,
    int bufferBytes,
    int timeoutMillis,
    boolean promiscuous,
    boolean immediate,
    Path outputDirectory,
    String fileBase,
    int rollMiB,
    Path httpOutputDirectory,
    Path tn3270OutputDirectory) {

  public CaptureConfig {
    if (iface == null || iface.isBlank()) {
      throw new IllegalArgumentException("iface must be provided");
    }
    if (outputDirectory == null) {
      throw new IllegalArgumentException("outputDirectory must be provided");
    }
    if (httpOutputDirectory == null) {
      throw new IllegalArgumentException("httpOutputDirectory must be provided");
    }
    if (fileBase == null || fileBase.isBlank()) {
      throw new IllegalArgumentException("fileBase must be provided");
    }
    if (rollMiB <= 0) {
      throw new IllegalArgumentException("rollMiB must be positive");
    }
  }

  public static CaptureConfig defaults() {
    return new CaptureConfig(
        "eth0",
        null,
        65_535,
        1_024 * 1_024 * 1_024,
        1_000,
        true,
        true,
        Path.of("out", "segments"),
        "segments",
        1_024,
        Path.of("out", "http"),
        Path.of("out", "tn3270"));
  }

  public static CaptureConfig fromArgs(String[] args) {
    Map<String, String> kv = new HashMap<>();
    if (args != null) {
      for (String arg : args) {
        if (arg == null || arg.isBlank()) continue;
        String[] parts = arg.split("=", 2);
        String key = parts[0];
        String value = parts.length > 1 ? parts[1] : "";
        kv.put(key, value);
      }
    }
    return fromMap(kv);
  }

  public static CaptureConfig fromMap(Map<String, String> args) {
    Map<String, String> kv = args == null ? Map.of() : new HashMap<>(args);
    CaptureConfig defaults = defaults();

    String iface = normalized(kv.getOrDefault("iface", defaults.iface()));
    if (iface == null) {
      throw new IllegalArgumentException("iface must be provided");
    }

    String filter = normalized(kv.getOrDefault("bpf", defaults.filter()));
    int snap = parseInt(kv.get("snap"), defaults.snaplen());
    int bufferBytes = Math.max(1, parseInt(kv.get("bufmb"), defaults.bufferBytes() / (1024 * 1024))) * 1024 * 1024;
    int timeout = parseInt(kv.get("timeout"), defaults.timeoutMillis());
    boolean promisc = parseBoolean(kv.get("promisc"), defaults.promiscuous());
    boolean immediate = parseBoolean(kv.get("immediate"), defaults.immediate());
    Path outputDir = parsePath(kv.get("out"), defaults.outputDirectory());
    String fileBase = normalized(kv.getOrDefault("fileBase", defaults.fileBase()));
    int rollMiB = parseInt(kv.get("rollMiB"), defaults.rollMiB());
    Path httpOut = parsePath(kv.get("httpOut"), defaults.httpOutputDirectory());
    Path tnOut = parsePath(kv.get("tnOut"), defaults.tn3270OutputDirectory());

    return new CaptureConfig(
        iface,
        filter,
        snap,
        bufferBytes,
        timeout,
        promisc,
        immediate,
        outputDir,
        fileBase,
        rollMiB,
        httpOut,
        tnOut);
  }

  private static String normalized(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static int parseInt(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static boolean parseBoolean(String value, boolean fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Boolean.parseBoolean(value.trim());
  }

  private static Path parsePath(String value, Path fallback) {
    String normalized = normalized(value);
    return normalized == null ? fallback : Path.of(normalized);
  }
}
