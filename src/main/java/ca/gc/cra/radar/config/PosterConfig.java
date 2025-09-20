package ca.gc.cra.radar.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PosterConfig {
  private final Path inputDirectory;
  private final Path outputDirectory;
  private final DecodeMode decodeMode;

  private PosterConfig(Path inputDirectory, Path outputDirectory, DecodeMode decodeMode) {
    this.inputDirectory = Objects.requireNonNull(inputDirectory, "inputDirectory");
    this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
    this.decodeMode = Objects.requireNonNull(decodeMode, "decodeMode");
  }

  public static PosterConfig fromMap(Map<String, String> args) {
    Objects.requireNonNull(args, "args");
    String in = args.get("in");
    if (in == null || in.isBlank()) {
      throw new IllegalArgumentException("missing required arg in=<directory>");
    }
    String out = args.get("out");
    if (out == null || out.isBlank()) {
      throw new IllegalArgumentException("missing required arg out=<directory>");
    }
    String decodeRaw = args.getOrDefault("decode", "none");
    DecodeMode mode = DecodeMode.fromString(decodeRaw);
    return new PosterConfig(Path.of(in), Path.of(out), mode);
  }

  public Path inputDirectory() {
    return inputDirectory;
  }

  public Path outputDirectory() {
    return outputDirectory;
  }

  public DecodeMode decodeMode() {
    return decodeMode;
  }

  public enum DecodeMode {
    NONE,
    TRANSFER,
    ALL;

    public static DecodeMode fromString(String value) {
      if (value == null) {
        return NONE;
      }
      switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "none" -> {
          return NONE;
        }
        case "transfer" -> {
          return TRANSFER;
        }
        case "all" -> {
          return ALL;
        }
        default -> throw new IllegalArgumentException("unknown decode mode: " + value);
      }
    }

    public boolean decodeTransferEncoding() {
      return this == TRANSFER || this == ALL;
    }

    public boolean decodeContentEncoding() {
      return this == ALL;
    }
  }
}
