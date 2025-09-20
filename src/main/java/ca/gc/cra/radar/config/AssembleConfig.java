package ca.gc.cra.radar.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AssembleConfig(
    Path inputDirectory,
    Path outputDirectory,
    boolean httpEnabled,
    boolean tnEnabled,
    Optional<Path> httpOutputDirectory,
    Optional<Path> tnOutputDirectory) {

  public AssembleConfig {
    Objects.requireNonNull(inputDirectory, "inputDirectory");
    Objects.requireNonNull(outputDirectory, "outputDirectory");
    httpOutputDirectory = sanitizeOptional(httpOutputDirectory);
    tnOutputDirectory = sanitizeOptional(tnOutputDirectory);
  }

  public static AssembleConfig defaults() {
    return new AssembleConfig(
        Path.of("./cap-out"),
        Path.of("./pairs-out"),
        true,
        false,
        Optional.empty(),
        Optional.empty());
  }

  public static AssembleConfig fromMap(Map<String, String> options) {
    AssembleConfig defaults = defaults();
    Path input = resolvePath(
        firstNonBlank(options.get("in"), options.get("segments"), defaults.inputDirectory().toString()));
    Path output = resolvePath(
        firstNonBlank(options.get("out"), options.get("--out"), defaults.outputDirectory().toString()));
    boolean httpEnabled = parseBoolean(options.get("httpEnabled"), true);
    boolean tnEnabled = parseBoolean(options.get("tnEnabled"), false);

    if (!httpEnabled && !tnEnabled) {
      throw new IllegalArgumentException("At least one protocol must be enabled");
    }

    Optional<Path> httpOut = optionalPath(firstNonBlank(options.get("httpOut"), options.get("--httpOut")));
    Optional<Path> tnOut = optionalPath(firstNonBlank(options.get("tnOut"), options.get("--tnOut")));

    return new AssembleConfig(input, output, httpEnabled, tnEnabled, httpOut, tnOut);
  }

  public Path effectiveHttpOut() {
    return httpOutputDirectory.orElseGet(() -> outputDirectory.resolve("http"));
  }

  public Path effectiveTnOut() {
    return tnOutputDirectory.orElseGet(() -> outputDirectory.resolve("tn3270"));
  }

  private static boolean parseBoolean(String value, boolean defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
  }

  private static Path resolvePath(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Path value must not be blank");
    }
    try {
      return Path.of(value);
    } catch (InvalidPathException ex) {
      throw new IllegalArgumentException("Invalid path: " + value, ex);
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return values.length == 0 ? "" : values[values.length - 1];
  }

  private static Optional<Path> optionalPath(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(resolvePath(value));
  }

  private static Optional<Path> sanitizeOptional(Optional<Path> candidate) {
    if (candidate == null) {
      return Optional.empty();
    }
    return candidate.map(Path::normalize);
  }
}
