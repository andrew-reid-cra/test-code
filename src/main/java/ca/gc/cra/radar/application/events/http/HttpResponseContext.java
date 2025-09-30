package ca.gc.cra.radar.application.events.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable view of a reconstructed HTTP response.
 *
 * @since RADAR 1.1.0
 */
public final class HttpResponseContext {
  private final int status;
  private final String reason;
  private final String httpVersion;
  private final Map<String, List<String>> headers;
  private final HttpBodyView body;
  private final long timestampMicros;

  /**
   * Creates a response context with pre-normalized header maps.
   *
   * @param status HTTP status code (negative when unavailable)
   * @param reason optional status reason phrase
   * @param httpVersion HTTP version token
   * @param headers case-insensitive header map keyed in lower case with ordered values
   * @param body lazily decoded body view
   * @param timestampMicros response timestamp in microseconds since epoch
   */
  public HttpResponseContext(
      int status,
      String reason,
      String httpVersion,
      Map<String, List<String>> headers,
      HttpBodyView body,
      long timestampMicros) {
    this.status = status;
    this.reason = reason;
    this.httpVersion = Objects.requireNonNull(httpVersion, "httpVersion");
    this.headers = copyMultiMap(headers);
    this.body = Objects.requireNonNull(body, "body");
    this.timestampMicros = timestampMicros;
  }

  /**
   * Returns the HTTP status code emitted by the server.
   *
   * @return status code or a negative value when unavailable
   */
  public int status() {
    return status;
  }

  /**
   * Provides the optional HTTP reason phrase.
   *
   * @return textual reason or {@code null} when not supplied on the wire
   */
  public String reason() {
    return reason;
  }

  /**
   * Returns the HTTP version token associated with the response.
   *
   * @return HTTP version value such as {@code HTTP/1.1}
   */
  public String httpVersion() {
    return httpVersion;
  }

  /**
   * Provides a case-insensitive view of the response headers.
   *
   * @return immutable header map keyed in lower case with ordered values
   */
  public Map<String, List<String>> headers() {
    return Map.copyOf(headers);
  }

  /**
   * Supplies the lazily decoded response body.
   *
   * @return body view capable of exposing bytes, text, or JSON on demand
   */
  public HttpBodyView body() {
    return body;
  }

  /**
   * Provides the capture timestamp measured in microseconds since the epoch.
   *
   * @return microsecond-resolution timestamp associated with the response
   */
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
