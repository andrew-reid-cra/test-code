package ca.gc.cra.radar.api;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility for turning {@code key=value} CLI arguments into a lookup map.
 * <p>Stateless and thread-safe.</p>
 *
 * @since RADAR 0.1-doc
 */
public final class CliArgsParser {
  private CliArgsParser() {}

  /**
   * Converts command-line arguments into a mutable map split on the first {@code '='}.
   *
   * @param args raw CLI arguments; {@code null} returns an empty map
   * @return mutable map keyed by the argument prefix prior to {@code '='}
   * @since RADAR 0.1-doc
   */
  public static Map<String, String> toMap(String[] args) {
    Map<String, String> map = new HashMap<>();
    if (args == null) return map;
    for (String arg : args) {
      int idx = arg.indexOf('=');
      if (idx > 0) {
        map.put(arg.substring(0, idx), arg.substring(idx + 1));
      }
    }
    return map;
  }
}
