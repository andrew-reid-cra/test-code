package ca.gc.cra.radar.infrastructure.protocol.http;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Port-friendly session extractor mirroring the legacy implementation.
 */
public final class HttpSessionExtractor {
  private final Set<String> cookieKeys;
  private final List<Pattern> headerPatterns;

  public HttpSessionExtractor(Collection<String> cookieKeys, Collection<String> headerRegexes) {
    this.cookieKeys = cookieKeys.stream()
        .map(k -> k.toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toSet());
    this.headerPatterns = headerRegexes.stream()
        .map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE))
        .toList();
  }

  public String fromRequestHeaders(Map<String, String> headers) {
    for (Map.Entry<String, String> e : headers.entrySet()) {
      String line = e.getKey() + ": " + e.getValue();
      for (Pattern p : headerPatterns) {
        var m = p.matcher(line);
        if (m.find()) {
          return "HDR:" + m.group(m.groupCount());
        }
      }
    }
    String cookie = headers.getOrDefault("cookie", null);
    if (cookie != null) {
      for (String part : cookie.split(";")) {
        int eq = part.indexOf('=');
        if (eq > 0) {
          String k = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
          if (cookieKeys.contains(k)) {
            return "SID:" + part.substring(eq + 1).trim();
          }
        }
      }
    }
    return null;
  }

  public String fromSetCookie(List<String> setCookies) {
    for (String sc : setCookies) {
      int eq = sc.indexOf('=');
      if (eq > 0) {
        String k = sc.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        if (cookieKeys.contains(k)) {
          int semi = sc.indexOf(';', eq + 1);
          String v = semi > 0 ? sc.substring(eq + 1, semi) : sc.substring(eq + 1);
          return "SID:" + v.trim();
        }
      }
    }
    return null;
  }
}


