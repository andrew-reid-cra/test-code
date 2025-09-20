package ca.gc.cra.radar.api;

import java.util.HashMap;
import java.util.Map;

public final class CliArgsParser {
  private CliArgsParser() {}

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


