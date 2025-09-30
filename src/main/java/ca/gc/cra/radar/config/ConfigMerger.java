package ca.gc.cra.radar.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Merges configuration from defaults, YAML, and CLI sources while enforcing precedence and invariants.
 */
public final class ConfigMerger {

  private ConfigMerger() {}

  /**
   * Builds an effective configuration map using precedence CLI > YAML > defaults.
   *
   * @param mode active pipeline mode
   * @param yaml optional YAML-derived settings for the mode
   * @param cli CLI key/value overrides (may be empty)
   * @param defaults embedded defaults for the mode
   * @param warn consumer invoked when a CLI key overrides a YAML key
   * @return immutable merged configuration map
   * @throws IllegalArgumentException when validation fails
   */
  public static Map<String, String> buildEffectiveConfig(
      String mode,
      Optional<Map<String, String>> yaml,
      Map<String, String> cli,
      Map<String, String> defaults,
      Consumer<String> warn) {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(yaml, "yaml");
    Map<String, String> defaultsCopy = defaults == null ? Map.of() : defaults;
    Map<String, String> yamlCopy = yaml.orElse(Map.of());
    Map<String, String> cliCopy = cli == null ? Map.of() : cli;

    Map<String, String> merged = new LinkedHashMap<>(defaultsCopy);
    merged.putAll(yamlCopy);

    if (cliCopy.containsKey("snap") && !cliCopy.containsKey("snaplen")) {
      merged.remove("snaplen");
    }
    if (cliCopy.containsKey("segmentsOut") && !cliCopy.containsKey("out")) {
      merged.remove("out");
    }
    if (cliCopy.containsKey("--httpOut") && !cliCopy.containsKey("httpOut")) {
      merged.remove("httpOut");
    }
    if (cliCopy.containsKey("--tnOut") && !cliCopy.containsKey("tnOut")) {
      merged.remove("tnOut");
    }
    if (!cliCopy.isEmpty()) {
      for (Map.Entry<String, String> entry : cliCopy.entrySet()) {
        String key = entry.getKey();
        if (key == null) {
          continue;
        }
        String value = entry.getValue();
        if (yamlCopy.containsKey(key) && warn != null) {
          warn.accept("CLI overrides YAML for key: " + key);
        }
        if (value != null) {
          merged.put(key, value);
        }
      }
    }

    validate(mode, merged);
    return Map.copyOf(merged);
  }

  private static void validate(String mode, Map<String, String> effective) {
    requireBootstrapWhenKafka(effective.get("ioMode"), effective);
    requireBootstrapWhenKafka(effective.get("posterOutMode"), effective);

    if ("assemble".equalsIgnoreCase(mode) || "live".equalsIgnoreCase(mode)) {
      boolean httpEnabled = parseBoolean(effective.get("httpEnabled"), true);
      boolean tnEnabled = parseBoolean(effective.get("tnEnabled"), false);
      if (!httpEnabled && !tnEnabled) {
        throw new IllegalArgumentException(
            "At least one of httpEnabled or tnEnabled must be true for " + mode);
      }
    }

    String bpf = trim(effective.get("bpf"));
    if (!bpf.isEmpty()) {
      boolean enableBpf = parseBoolean(effective.get("enableBpf"), false);
      if (!enableBpf) {
        throw new IllegalArgumentException("bpf requires --enable-bpf acknowledgement");
      }
    }
  }

  private static void requireBootstrapWhenKafka(String modeValue, Map<String, String> effective) {
    if (modeValue != null && modeValue.trim().equalsIgnoreCase("kafka")) {
      String bootstrap = trim(effective.get("kafkaBootstrap"));
      if (bootstrap.isEmpty()) {
        throw new IllegalArgumentException("kafkaBootstrap is required when ioMode=KAFKA");
      }
    }
  }

  private static boolean parseBoolean(String value, boolean defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value.trim());
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}


