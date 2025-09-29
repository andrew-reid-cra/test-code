package ca.gc.cra.radar.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Loads RADAR configuration from a YAML document and flattens sections into simple key/value maps.
 */
public final class YamlConfigLoader {

  private YamlConfigLoader() {}

  /**
   * Loads YAML from {@code path} and merges the {@code common} section with the requested {@code mode} section.
   *
   * @param path location of the YAML configuration
   * @param mode pipeline mode (capture, live, assemble, poster)
   * @return optional flat map containing merged configuration
   * @throws IOException when the file cannot be read
   * @throws IllegalArgumentException when the YAML structure is invalid
   */
  public static Optional<Map<String, String>> load(Path path, String mode) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(mode, "mode");
    if (!Files.exists(path)) {
      return Optional.empty();
    }

    String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      Object document = new Yaml().load(reader);
      if (document == null) {
        return Optional.of(Map.of());
      }
      Map<String, Object> root = asMap(document, "root");

      Map<String, String> flattened = new LinkedHashMap<>();
      Object commonSection = findSection(root, "common");
      if (commonSection != null) {
        flatten(asMap(commonSection, "common"), "", flattened);
      }
      Object modeSection = findSection(root, normalizedMode);
      if (modeSection instanceof Map<?, ?> modeMap) {
        flatten(asMap(modeMap, normalizedMode), "", flattened);
      }

      return Optional.of(Map.copyOf(flattened));
    } catch (YAMLException ex) {
      throw new IllegalArgumentException("Failed to parse YAML config at " + path, ex);
    }
  }

  private static Map<String, Object> asMap(Object node, String context) {
    if (!(node instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException(context + " section must be a mapping");
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(context + " section contains non-string key");
      }
      map.put(key, entry.getValue());
    }
    return map;
  }

  private static Object findSection(Map<String, Object> root, String key) {
    for (Map.Entry<String, Object> entry : root.entrySet()) {
      if (entry.getKey() != null
          && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(key)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static void flatten(Map<String, Object> source, String prefix, Map<String, String> target) {
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("YAML contains blank keys");
      }
      String composite = prefix.isEmpty() ? key : prefix + '.' + key;
      Object value = entry.getValue();
      if (value == null) {
        target.put(composite, "");
      } else if (value instanceof Map<?, ?> nested) {
        flatten(asMap(nested, composite), composite, target);
      } else if (value instanceof Iterable<?>) {
        throw new IllegalArgumentException("YAML arrays are not supported for key " + composite);
      } else {
        target.put(composite, value.toString());
      }
    }
  }
}
