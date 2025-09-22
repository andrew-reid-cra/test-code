package ca.gc.cra.radar.api;

import ca.gc.cra.radar.validation.Strings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility for turning {@code key=value} CLI arguments into a lookup map.
 * <p>Stateless and thread-safe.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class CliArgsParser {
  private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

  private CliArgsParser() {}

  /**
   * Converts command-line arguments into a mutable map split on the first {@code '='}.
   *
   * @param args raw CLI arguments; {@code null} returns an empty map
   * @return mutable map keyed by the argument prefix prior to {@code '='}
   * @since RADAR 0.1-doc
   */
  public static Map<String, String> toMap(String[] args) {
    Map<String, String> map = new LinkedHashMap<>();
    if (args == null) {
      return map;
    }
    for (String raw : args) {
      if (raw == null) {
        continue;
      }
      String arg = raw.trim();
      if (arg.isEmpty()) {
        continue;
      }
      int idx = arg.indexOf('=');
      if (idx <= 0 || idx == arg.length() - 1) {
        throw new IllegalArgumentException("argument must be key=value (was '" + raw + "')");
      }
      String key = arg.substring(0, idx).trim();
      String value = arg.substring(idx + 1).trim();
      validateKey(key);
      validateValue(key, value);
      map.put(key, value);
    }
    return map;
  }

  private static void validateKey(String key) {
    if (!KEY_PATTERN.matcher(key).matches()) {
      throw new IllegalArgumentException("invalid argument name: " + key);
    }
    if (containsControl(key)) {
      throw new IllegalArgumentException("argument name must not contain control characters: " + key);
    }
  }

  private static void validateValue(String key, String value) {
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("argument " + key + " must not contain null bytes");
    }
    if (containsControl(value)) {
      throw new IllegalArgumentException(
          "argument " + key + " must not contain control characters");
    }
    // Blank values are allowed for options toggled explicitly; downstream validators will reject as needed.
    if (!value.isEmpty()) {
      Strings.requireNonBlank(key, value);
    }
  }

  private static boolean containsControl(CharSequence value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }
}
