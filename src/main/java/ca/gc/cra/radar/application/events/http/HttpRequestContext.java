package ca.gc.cra.radar.application.events.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable view of a reconstructed HTTP request.
 *
 * @since RADAR 1.1.0
 */
public final class HttpRequestContext {
  private final String method;
  private final String path;
  private final String rawTarget;
  private final String httpVersion;
  private final Map<String, List<String>> headers;
  private final Map<String, String> cookies;
  private final Map<String, String> query;
  private final HttpBodyView body;
  private final String clientIp;
  private final int clientPort;
  private final String serverIp;
  private final int serverPort;
  private final long timestampMicros;

  /**
   * Creates a request context with pre-normalized header, cookie, and query maps.
   *
   * @param method HTTP method
   * @param path normalized request path (no query string)
   * @param rawTarget raw request target including query string (e.g., {@code /foo?bar=baz})
   * @param httpVersion HTTP version token (e.g., {@code HTTP/1.1})
   * @param headers case-insensitive header map keyed in lower case with ordered values
   * @param cookies cookie map keyed in lower case
   * @param query query parameter map keyed in lower case with first values
   * @param body lazily decoded body view
   * @param clientIp client IP address
   * @param clientPort client port
   * @param serverIp server IP address
   * @param serverPort server port
   * @param timestampMicros timestamp associated with the request in microseconds since epoch
   */
  public HttpRequestContext(
      String method,
      String path,
      String rawTarget,
      String httpVersion,
      Map<String, List<String>> headers,
      Map<String, String> cookies,
      Map<String, String> query,
      HttpBodyView body,
      String clientIp,
      int clientPort,
      String serverIp,
      int serverPort,
      long timestampMicros) {
    this.method = Objects.requireNonNull(method, "method");
    this.path = Objects.requireNonNull(path, "path");
    this.rawTarget = Objects.requireNonNull(rawTarget, "rawTarget");
    this.httpVersion = Objects.requireNonNull(httpVersion, "httpVersion");
    this.headers = copyMultiMap(headers);
    this.cookies = Map.copyOf(Objects.requireNonNull(cookies, "cookies"));
    this.query = Map.copyOf(Objects.requireNonNull(query, "query"));
    this.body = Objects.requireNonNull(body, "body");
    this.clientIp = Objects.requireNonNull(clientIp, "clientIp");
    this.clientPort = clientPort;
    this.serverIp = Objects.requireNonNull(serverIp, "serverIp");
    this.serverPort = serverPort;
    this.timestampMicros = timestampMicros;
  }

  public String method() {
    return method;
  }

  public String path() {
    return path;
  }

  public String rawTarget() {
    return rawTarget;
  }

  public String httpVersion() {
    return httpVersion;
  }

  public Map<String, List<String>> headers() {
    return headers;
  }

  public Map<String, String> cookies() {
    return cookies;
  }

  public Map<String, String> query() {
    return query;
  }

  public HttpBodyView body() {
    return body;
  }

  public String clientIp() {
    return clientIp;
  }

  public int clientPort() {
    return clientPort;
  }

  public String serverIp() {
    return serverIp;
  }

  public int serverPort() {
    return serverPort;
  }

  public long timestampMicros() {
    return timestampMicros;
  }

  /**
   * Returns the first header value ignoring case.
   *
   * @param name header name
   * @return optional header value
   */
  public Optional<String> header(String name) {
    List<String> values = headerValues(name);
    return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
  }

  /**
   * Returns all header values for the supplied name ignoring case.
   *
   * @param name header name
   * @return immutable list of header values; empty when not present
   */
  public List<String> headerValues(String name) {
    if (name == null) {
      return List.of();
    }
    return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
  }

  /**
   * Returns a cookie value ignoring case.
   *
   * @param name cookie name
   * @return optional cookie value
   */
  public Optional<String> cookie(String name) {
    if (name == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(cookies.get(name.toLowerCase(Locale.ROOT)));
  }

  /**
   * Returns the first query parameter value ignoring case.
   *
   * @param name parameter name
   * @return optional query parameter value
   */
  public Optional<String> queryParam(String name) {
    if (name == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(query.get(name.toLowerCase(Locale.ROOT)));
  }

  private static Map<String, List<String>> copyMultiMap(Map<String, List<String>> source) {
    Objects.requireNonNull(source, "headers");
    Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : source.entrySet()) {
      List<String> values = entry.getValue() == null ? List.of() : List.copyOf(entry.getValue());
      copy.put(entry.getKey(), values);
    }
    return Map.copyOf(copy);
  }
}
