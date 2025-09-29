package ca.gc.cra.radar.api;

import java.util.Map;

/**
 * Shared helpers for mixing CLI flag semantics with YAML/Map based configuration sources.
 */
final class ConfigCliUtils {

  private ConfigCliUtils() {}

  static String extractConfigPath(Map<String, String> args) {
    if (args == null || args.isEmpty()) {
      return null;
    }
    for (String key : new String[] {"config", "--config"}) {
      String value = args.remove(key);
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  static boolean parseBoolean(Map<String, String> map, String key) {
    return parseBoolean(map, key, false);
  }

  static boolean parseBoolean(Map<String, String> map, String key, boolean defaultValue) {
    if (map == null) {
      return defaultValue;
    }
    String value = map.get(key);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value.trim());
  }
}
