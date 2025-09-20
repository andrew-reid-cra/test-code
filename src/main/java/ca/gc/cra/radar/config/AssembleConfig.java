package ca.gc.cra.radar.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record AssembleConfig(Path inputDirectory, Path outputDirectory, boolean httpEnabled, boolean tnEnabled) {
  public AssembleConfig {
    Objects.requireNonNull(inputDirectory, "inputDirectory");
    Objects.requireNonNull(outputDirectory, "outputDirectory");
  }

  public static AssembleConfig defaults() {
    return new AssembleConfig(Path.of("./cap-out"), Path.of("./pairs-out"), true, false);
  }

  public static AssembleConfig fromMap(Map<String, String> options) {
    AssembleConfig defaults = defaults();
    Path input = resolvePath(firstNonBlank(options.get("in"), options.get("segments"), defaults.inputDirectory().toString()));
    Path output = resolvePath(firstNonBlank(options.get("out"), options.get("httpOut"), defaults.outputDirectory().toString()));
    boolean httpEnabled = parseBoolean(options.get("httpEnabled"), true);
    boolean tnEnabled = parseBoolean(options.get("tnEnabled"), false);

    if (!httpEnabled && !tnEnabled) {
      throw new IllegalArgumentException("At least one protocol must be enabled");
    }

    return new AssembleConfig(input, output, httpEnabled, tnEnabled);
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
}
