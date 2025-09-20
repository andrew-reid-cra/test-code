package ca.gc.cra.radar.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PosterConfig {
  private final Optional<ProtocolConfig> http;
  private final Optional<ProtocolConfig> tn3270;
  private final DecodeMode decodeMode;

  private PosterConfig(
      Optional<ProtocolConfig> http,
      Optional<ProtocolConfig> tn3270,
      DecodeMode decodeMode) {
    this.http = Objects.requireNonNull(http, "http");
    this.tn3270 = Objects.requireNonNull(tn3270, "tn3270");
    this.decodeMode = Objects.requireNonNull(decodeMode, "decodeMode");
  }

  public static PosterConfig fromMap(Map<String, String> args) {
    Objects.requireNonNull(args, "args");
    DecodeMode decodeMode = DecodeMode.fromString(args.getOrDefault("decode", "none"));

    Optional<ProtocolConfig> http = buildProtocolConfig(
        firstNonBlank(args, "httpIn", "--httpIn", "in", "--in"),
        firstNonBlank(args, "httpOut", "--httpOut", "out", "--out"));

    Optional<ProtocolConfig> tn = buildProtocolConfig(
        firstNonBlank(args, "tnIn", "--tnIn"),
        firstNonBlank(args, "tnOut", "--tnOut", "out", "--out"));

    if (http.isEmpty() && tn.isEmpty()) {
      throw new IllegalArgumentException("missing inputs: specify httpIn/tnIn directories");
    }

    return new PosterConfig(http, tn, decodeMode);
  }

  public Optional<ProtocolConfig> http() {
    return http;
  }

  public Optional<ProtocolConfig> tn3270() {
    return tn3270;
  }

  public DecodeMode decodeMode() {
    return decodeMode;
  }

  private static Optional<ProtocolConfig> buildProtocolConfig(String inRaw, String outRaw) {
    Optional<Path> in = optionalPath(inRaw);
    Optional<Path> out = optionalPath(outRaw);
    if (in.isEmpty() || out.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new ProtocolConfig(in.get(), out.get()));
  }

  private static Optional<Path> optionalPath(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(resolvePath(value));
  }

  private static Path resolvePath(String value) {
    try {
      return Path.of(value);
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException("Invalid path: " + value, ex);
    }
  }

  private static String firstNonBlank(Map<String, String> args, String... keys) {
    for (String key : keys) {
      if (key == null) {
        continue;
      }
      String value = args.get(key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  public record ProtocolConfig(Path inputDirectory, Path outputDirectory) {
    public ProtocolConfig {
      Objects.requireNonNull(inputDirectory, "inputDirectory");
      Objects.requireNonNull(outputDirectory, "outputDirectory");
    }
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
